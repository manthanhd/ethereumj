package org.ethereum.net.eth.handler;

import io.netty.channel.ChannelHandlerContext;
import org.ethereum.core.*;
import org.ethereum.db.BlockStore;
import org.ethereum.net.eth.EthVersion;
import org.ethereum.net.eth.message.*;
import org.ethereum.net.message.ReasonCode;
import org.ethereum.sync.SyncQueue;
import org.ethereum.sync.listener.CompositeSyncListener;
import org.ethereum.sync.SyncState;
import org.ethereum.sync.SyncStatistics;
import org.ethereum.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.util.*;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.reverse;
import static java.util.Collections.singletonList;
import static org.ethereum.net.eth.EthVersion.V62;
import static org.ethereum.net.message.ReasonCode.USELESS_PEER;
import static org.ethereum.sync.SyncState.*;
import static org.ethereum.sync.SyncState.BLOCK_RETRIEVING;
import static org.ethereum.util.BIUtil.isLessThan;
import static org.spongycastle.util.encoders.Hex.toHexString;

/**
 * Eth 62
 *
 * @author Mikhail Kalinin
 * @since 04.09.2015
 */
@Component
@Scope("prototype")
public class Eth62 extends EthHandler {

    protected static final int MAX_HASHES_TO_SEND = 65536;

    private final static Logger logger = LoggerFactory.getLogger("sync");
    private final static Logger loggerNet = LoggerFactory.getLogger("net");

    @Autowired
    protected BlockStore blockstore;

    @Autowired
    protected SyncQueue queue;

    @Autowired
    protected PendingState pendingState;

    @Autowired
    protected CompositeSyncListener compositeSyncListener;

    protected EthState ethState = EthState.INIT;

    protected SyncState syncState = IDLE;
    protected boolean syncDone = false;
    protected boolean processTransactions = false;

    /**
     * Last block hash to be asked from the peer,
     * is set on header retrieving start
     */
    protected byte[] lastHashToAsk;

    /**
     * Hash of the eldest header fetched from remote peer
     * It's updated each time when new header message processed
     */
    protected byte[] eldestHash;

    /**
     * Number and hash of best known remote block
     */
    protected BlockIdentifier bestKnownBlock;

    protected boolean commonAncestorFound = true;

    /**
     * Header list sent in GET_BLOCK_BODIES message,
     * used to create blocks from headers and bodies
     * also, is useful when returned BLOCK_BODIES msg doesn't cover all sent hashes
     * or in case when peer is disconnected
     */
    protected final List<BlockHeaderWrapper> sentHeaders = Collections.synchronizedList(new ArrayList<BlockHeaderWrapper>());

    protected final SyncStatistics syncStats = new SyncStatistics();

    protected GetBlockHeadersMessage headersRequest;

    private BlockWrapper gapBlock;

    public Eth62() {
        super(V62);
    }

    @PostConstruct
    private void init() {
        maxHashesAsk = config.maxHashesAsk();
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, EthMessage msg) throws InterruptedException {

        super.channelRead0(ctx, msg);

        switch (msg.getCommand()) {
            case STATUS:
                processStatus((StatusMessage) msg, ctx);
                break;
            case NEW_BLOCK_HASHES:
                processNewBlockHashes((NewBlockHashesMessage) msg);
                break;
            case TRANSACTIONS:
                processTransactions((TransactionsMessage) msg);
                break;
            case GET_BLOCK_HEADERS:
                processGetBlockHeaders((GetBlockHeadersMessage) msg);
                break;
            case BLOCK_HEADERS:
                processBlockHeaders((BlockHeadersMessage) msg);
                break;
            case GET_BLOCK_BODIES:
                processGetBlockBodies((GetBlockBodiesMessage) msg);
                break;
            case BLOCK_BODIES:
                processBlockBodies((BlockBodiesMessage) msg);
                break;
            case NEW_BLOCK:
                processNewBlock((NewBlockMessage) msg);
                break;
            default:
                break;
        }
    }

    /*************************
     *    Message Sending    *
     *************************/

    @Override
    public void sendStatus() {
        byte protocolVersion = version.getCode();
        int networkId = config.networkId();

        BigInteger totalDifficulty = blockchain.getTotalDifficulty();
        byte[] bestHash = blockchain.getBestBlockHash();
        StatusMessage msg = new StatusMessage(protocolVersion, networkId,
                ByteUtil.bigIntegerToBytes(totalDifficulty), bestHash, Blockchain.GENESIS_HASH);
        sendMessage(msg);
    }

    @Override
    public void sendNewBlockHashes(Block block) {

        BlockIdentifier identifier = new BlockIdentifier(block.getHash(), block.getNumber());
        NewBlockHashesMessage msg = new NewBlockHashesMessage(singletonList(identifier));
        sendMessage(msg);
    }

    @Override
    public void sendTransaction(List<Transaction> txs) {
        TransactionsMessage msg = new TransactionsMessage(txs);
        sendMessage(msg);
    }

    protected void sendGetBlockHeaders(long blockNumber, int maxBlocksAsk) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: send GetBlockHeaders, blockNumber [{}], maxBlocksAsk [{}]",
                channel.getPeerIdShort(),
                blockNumber,
                maxBlocksAsk
        );

        headersRequest = new GetBlockHeadersMessage(blockNumber, maxBlocksAsk);

        sendMessage(headersRequest);
    }

    protected void sendGetBlockHeaders(byte[] blockHash, int maxBlocksAsk, int skip, boolean reverse) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: send GetBlockHeaders, blockHash [{}], maxBlocksAsk [{}], skip[{}], reverse [{}]",
                channel.getPeerIdShort(),
                "0x" + toHexString(blockHash).substring(0, 8),
                maxBlocksAsk, skip, reverse
        );

        headersRequest = new GetBlockHeadersMessage(0, blockHash, maxBlocksAsk, skip, reverse);

        sendMessage(headersRequest);
    }

    protected boolean sendGetBlockBodies() {

        List<BlockHeaderWrapper> headers = queue.pollHeaders();
        if (headers.isEmpty()) {
            if(logger.isTraceEnabled()) logger.trace(
                    "Peer {}: no more headers in queue, idle",
                    channel.getPeerIdShort()
            );
            changeState(IDLE);
            return false;
        }

        sentHeaders.clear();
        sentHeaders.addAll(headers);

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: send GetBlockBodies, hashes.count [{}]",
                channel.getPeerIdShort(),
                sentHeaders.size()
        );

        List<byte[]> hashes = new ArrayList<>(headers.size());
        for (BlockHeaderWrapper header : headers) {
            hashes.add(header.getHash());
        }

        GetBlockBodiesMessage msg = new GetBlockBodiesMessage(hashes);

        sendMessage(msg);

        return true;
    }

    @Override
    public void sendNewBlock(Block block) {
        BigInteger parentTD = blockstore.getTotalDifficultyForHash(block.getParentHash());
        byte[] td = ByteUtil.bigIntegerToBytes(parentTD.add(new BigInteger(1, block.getDifficulty())));
        NewBlockMessage msg = new NewBlockMessage(block, td);
        sendMessage(msg);
    }

    /*************************
     *  Message Processing   *
     *************************/

    protected void processStatus(StatusMessage msg, ChannelHandlerContext ctx) throws InterruptedException {
        channel.getNodeStatistics().ethHandshake(msg);
        ethereumListener.onEthStatusUpdated(channel, msg);

        try {
            if (!Arrays.equals(msg.getGenesisHash(), Blockchain.GENESIS_HASH)
                    || msg.getProtocolVersion() != version.getCode()) {
                loggerNet.info("Removing EthHandler for {} due to protocol incompatibility", ctx.channel().remoteAddress());
                ethState = EthState.STATUS_FAILED;
                disconnect(ReasonCode.INCOMPATIBLE_PROTOCOL);
                ctx.pipeline().remove(this); // Peer is not compatible for the 'eth' sub-protocol
                return;
            } else if (msg.getNetworkId() != config.networkId()) {
                ethState = EthState.STATUS_FAILED;
                disconnect(ReasonCode.NULL_IDENTITY);
                return;
            } else if (peerDiscoveryMode) {
                loggerNet.debug("Peer discovery mode: STATUS received, disconnecting...");
                disconnect(ReasonCode.REQUESTED);
                ctx.close().sync();
                ctx.disconnect().sync();
                return;
            }
        } catch (NoSuchElementException e) {
            loggerNet.debug("EthHandler already removed");
            return;
        }

        // update bestKnownBlock info
        sendGetBlockHeaders(msg.getBestHash(), 1, 0, false);
    }

    protected void processNewBlockHashes(NewBlockHashesMessage msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: processing NewBlockHashes, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockIdentifiers().size()
        );

        List<BlockIdentifier> identifiers = msg.getBlockIdentifiers();

        if (identifiers.isEmpty()) return;

        updateBestBlock(identifiers);

        compositeSyncListener.onNewBlockNumber(bestKnownBlock.getNumber());

        // queueing new blocks doesn't make sense
        // while Long sync is in progress
        if (!syncDone) return;

        if (syncState != HASH_RETRIEVING) {
            long firstBlockNumber = identifiers.get(0).getNumber();
            long lastBlockNumber = identifiers.get(identifiers.size() - 1).getNumber();
            int maxBlocksAsk = (int) (lastBlockNumber - firstBlockNumber + 1);
            sendGetBlockHeaders(firstBlockNumber, maxBlocksAsk);
        }
    }

    protected void processTransactions(TransactionsMessage msg) {
        if(!processTransactions) {
            return;
        }

        List<Transaction> txSet = msg.getTransactions();
        pendingState.addWireTransactions(txSet);
    }

    protected void processGetBlockHeaders(GetBlockHeadersMessage msg) {
        List<BlockHeader> headers = blockchain.getListOfHeadersStartFrom(
                msg.getBlockIdentifier(),
                msg.getSkipBlocks(),
                min(msg.getMaxHeaders(), MAX_HASHES_TO_SEND),
                msg.isReverse()
        );

        BlockHeadersMessage response = new BlockHeadersMessage(headers);
        sendMessage(response);
    }

    protected void processBlockHeaders(BlockHeadersMessage msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: processing BlockHeaders, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockHeaders().size()
        );

        if (!isValid(msg)) {

            dropConnection();
            return;
        }

        List<BlockHeader> received = msg.getBlockHeaders();

        if (ethState == EthState.INIT)
            processInitHeaders(received);
        else if (!syncDone)
            processHeaderRetrieving(received);
        else if (syncState != HASH_RETRIEVING)
            processNewBlockHeaders(received);
        else if (!commonAncestorFound)
            processForkCoverage(received);
        else
            processGapRecovery(received);
    }

    protected void processGetBlockBodies(GetBlockBodiesMessage msg) {
        List<byte[]> bodies = blockchain.getListOfBodiesByHashes(msg.getBlockHashes());

        BlockBodiesMessage response = new BlockBodiesMessage(bodies);
        sendMessage(response);
    }

    protected void processBlockBodies(BlockBodiesMessage msg) {

        if (logger.isTraceEnabled()) logger.trace(
                "Peer {}: process BlockBodies, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockBodies().size()
        );

        if (!isValid(msg)) {

            dropConnection();
            return;
        }

        syncStats.addBlocks(msg.getBlockBodies().size());

        List<Block> blocks = validateAndMerge(msg);

        if (blocks == null) {

            // headers will be returned by #onShutdown()
            dropConnection();
            return;
        }

        returnHeaders();

        queue.addList(blocks, channel.getNodeId());

        if (syncState == BLOCK_RETRIEVING) {
            sendGetBlockBodies();
        }
    }

    protected void processNewBlock(NewBlockMessage newBlockMessage) {

        Block newBlock = newBlockMessage.getBlock();

        logger.info("New block received: block.index [{}]", newBlock.getNumber());

        // skip new block if TD is lower than ours
        if (isLessThan(newBlockMessage.getDifficultyAsBigInt(), blockchain.getTotalDifficulty())) {
            logger.trace(
                    "New block difficulty lower than ours: [{}] vs [{}], skip",
                    newBlockMessage.getDifficultyAsBigInt(),
                    blockchain.getTotalDifficulty()
            );
            return;
        }

        channel.getNodeStatistics().setEthTotalDifficulty(newBlockMessage.getDifficultyAsBigInt());

        updateBestBlock(newBlock);

        compositeSyncListener.onNewBlockNumber(newBlock.getNumber());

        // queueing new blocks doesn't make sense
        // while Long sync is in progress
        if (!syncDone) return;

        if (!queue.validateAndAddNewBlock(newBlock, channel.getNodeId())) {
            dropConnection();
        }
    }

    /*************************
     *    Sync Management    *
     *************************/

    @Override
    public void changeState(SyncState newState) {

        if (syncState == newState) {
            return;
        }

        logger.trace(
                "Peer {}: changing state from {} to {}",
                channel.getPeerIdShort(),
                syncState,
                newState
        );

        if (newState == HASH_RETRIEVING) {
            syncStats.reset();
            startHeaderRetrieving();
        }
        if (newState == BLOCK_RETRIEVING) {
            syncStats.reset();
            if (!sendGetBlockBodies()) {
                newState = IDLE;
            }
        }
        syncState = newState;
    }

    @Override
    public void onShutdown() {
        changeState(IDLE);
        returnHeaders();
    }

    @Override
    public void recoverGap(BlockWrapper block) {
        syncState = HASH_RETRIEVING;
        startGapRecovery(block);
    }

    protected void processInitHeaders(List<BlockHeader> received) {
        BlockHeader first = received.get(0);
        updateBestBlock(first);
        ethState = EthState.STATUS_SUCCEEDED;
        logger.trace(
                "Peer {}: init request succeeded, best known block {}",
                channel.getPeerIdShort(), bestKnownBlock
        );
    }

    protected void processHeaderRetrieving(List<BlockHeader> received) {

        // treat empty headers response as end of header sync
        if (received.isEmpty()) {
            changeState(DONE_HASH_RETRIEVING);
        } else {
            syncStats.addHeaders(received.size());

            logger.debug("Adding " + received.size() + " headers to the queue.");

            if (!queue.validateAndAddHeaders(received, channel.getNodeId())) {

                dropConnection();
                return;
            }
        }

        if (syncState == HASH_RETRIEVING) {
            BlockHeader latest = received.get(received.size() - 1);
            eldestHash = latest.getHash();
            sendGetBlockHeaders(latest.getNumber() + 1, maxHashesAsk);
        }

        if (syncState == DONE_HASH_RETRIEVING) {
            logger.info(
                    "Peer {}: header sync completed, [{}] headers in queue",
                    channel.getPeerIdShort(),
                    queue.headerStoreSize()
            );
        }
    }

    protected void processNewBlockHeaders(List<BlockHeader> received) {

        logger.debug("Adding " + received.size() + " headers to the queue.");

        if (!queue.validateAndAddHeaders(received, channel.getNodeId()))
            dropConnection();
    }

    protected void processGapRecovery(List<BlockHeader> received) {

        // treat empty headers response as end of header sync
        if (received.isEmpty()) {
            changeState(BLOCK_RETRIEVING);
        } else {
            syncStats.addHeaders(received.size());

            List<BlockHeader> adding = new ArrayList<>(received.size());
            for(BlockHeader header : received) {

                adding.add(header);

                if (Arrays.equals(header.getHash(), lastHashToAsk)) {
                    changeState(BLOCK_RETRIEVING);
                    logger.trace("Peer {}: got terminal hash [{}]", channel.getPeerIdShort(), toHexString(lastHashToAsk));
                    break;
                }
            }

            logger.debug("Adding " + adding.size() + " headers to the queue.");

            if (!queue.validateAndAddHeaders(adding, channel.getNodeId())) {

                dropConnection();
                return;
            }
        }

        if (syncState == HASH_RETRIEVING) {
            long lastNumber = received.get(received.size() - 1).getNumber();
            sendGetBlockHeaders(lastNumber + 1, maxHashesAsk);
        }

        if (syncState == BLOCK_RETRIEVING) {
            logger.info(
                    "Peer {}: header sync completed, [{}] headers in queue",
                    channel.getPeerIdShort(),
                    queue.headerStoreSize()
            );
        }
    }

    protected void startHeaderRetrieving() {

        lastHashToAsk = null;
        commonAncestorFound = true;

        if (logger.isInfoEnabled()) logger.info(
                "Peer {}: HASH_RETRIEVING initiated, askLimit [{}]",
                channel.getPeerIdShort(),
                maxHashesAsk
        );

        Block latest = queue.getLastBlock();
        if (latest == null) {
            latest = bestBlock;
        }

        eldestHash = latest.getHash();
        long blockNumber = latest.getNumber();

        sendGetBlockHeaders(blockNumber + 1, maxHashesAsk);
    }

    private void returnHeaders() {
        if(logger.isDebugEnabled()) logger.debug(
                "Peer {}: return [{}] headers back to store",
                channel.getPeerIdShort(),
                sentHeaders.size()
        );

        synchronized (sentHeaders) {
            queue.returnHeaders(sentHeaders);
        }

        sentHeaders.clear();
    }

    private void updateBestBlock(Block block) {
        bestKnownBlock = new BlockIdentifier(block.getHash(), block.getNumber());
    }

    private void updateBestBlock(BlockHeader header) {
        bestKnownBlock = new BlockIdentifier(header.getHash(), header.getNumber());
    }

    private void updateBestBlock(List<BlockIdentifier> identifiers) {

        for (BlockIdentifier id : identifiers)
            if (bestKnownBlock == null || id.getNumber() > bestKnownBlock.getNumber()) {
                bestKnownBlock = id;
            }
    }

    /*************************
     *     Fork Coverage     *
     *************************/

    private static final int FORK_COVER_BATCH_SIZE = 192;

    protected void startGapRecovery(BlockWrapper block) {

        gapBlock = block;
        lastHashToAsk = gapBlock.getHash();

        if (logger.isInfoEnabled()) logger.info(
                "Peer {}: HASH_RETRIEVING initiated, lastHashToAsk [{}], askLimit [{}]",
                channel.getPeerIdShort(),
                toHexString(lastHashToAsk),
                maxHashesAsk
        );

        commonAncestorFound = false;
        eldestHash = null;

        if (isNegativeGap()) {

            logger.trace("Peer {}: start fetching remote fork", channel.getPeerIdShort());
            sendGetBlockHeaders(gapBlock.getHash(), FORK_COVER_BATCH_SIZE, 0, true);
            return;
        }

        logger.trace("Peer {}: start looking for common ancestor", channel.getPeerIdShort());

        long bestNumber = bestBlock.getNumber();
        long blockNumber = max(0, bestNumber - FORK_COVER_BATCH_SIZE + 1);
        sendGetBlockHeaders(blockNumber, min(FORK_COVER_BATCH_SIZE, (int) (bestNumber - blockNumber + 1)));
    }

    private void processForkCoverage(List<BlockHeader> received) {

        if (!isNegativeGap()) reverse(received);

        ListIterator<BlockHeader> it = received.listIterator();

        if (isNegativeGap()) {

            // gap block didn't come, drop remote peer
            if (!Arrays.equals(it.next().getHash(), gapBlock.getHash())) {

                logger.info("Peer {}: invalid response, gap block is missed", channel.getPeerIdShort());
                dropConnection();
                return;
            }
        }

        // start downloading hashes from blockNumber of the block with known hash
        List<BlockHeader> headers = new ArrayList<>();
        while (it.hasNext()) {
            BlockHeader header = it.next();
            if (blockchain.isBlockExist(header.getHash())) {
                commonAncestorFound = true;
                logger.trace(
                        "Peer {}: common ancestor found: block.number {}, block.hash {}",
                        channel.getPeerIdShort(),
                        header.getNumber(),
                        toHexString(header.getHash())
                );

                break;
            }
            headers.add(header);
        }

        if (!commonAncestorFound) {

            logger.info("Peer {}: invalid response, common ancestor is not found", channel.getPeerIdShort());
            dropConnection();
            return;
        }

        // add missed headers
        queue.validateAndAddHeaders(headers, channel.getNodeId());

        if (isNegativeGap()) {

            // fork headers should already be fetched here
            logger.trace("Peer {}: remote fork is fetched", channel.getPeerIdShort());
            changeState(BLOCK_RETRIEVING);
            return;
        }

        // start header sync
        sendGetBlockHeaders(bestBlock.getNumber() + 1, maxHashesAsk);
    }

    private boolean isNegativeGap() {

        if (gapBlock == null) return false;

        return gapBlock.getNumber() <= bestBlock.getNumber();
    }

    /*************************
     *   Getters, setters    *
     *************************/

    @Override
    public boolean isHashRetrievingDone() {
        return syncState == DONE_HASH_RETRIEVING;
    }

    @Override
    public boolean isHashRetrieving() {
        return syncState == HASH_RETRIEVING;
    }

    @Override
    public boolean hasStatusPassed() {
        return ethState != EthState.INIT;
    }

    @Override
    public boolean hasStatusSucceeded() {
        return ethState == EthState.STATUS_SUCCEEDED;
    }

    @Override
    public boolean isIdle() {
        return syncState == IDLE;
    }

    @Override
    public void enableTransactions() {
        processTransactions = true;
    }

    @Override
    public void disableTransactions() {
        processTransactions = false;
    }

    @Override
    public SyncStatistics getStats() {
        return syncStats;
    }

    @Override
    public EthVersion getVersion() {
        return version;
    }

    @Override
    public void onSyncDone(boolean done) {
        syncDone = done;
    }

    /*************************
     *       Validation      *
     *************************/

    @Nullable
    private List<Block> validateAndMerge(BlockBodiesMessage response) {

        List<byte[]> bodyList = response.getBlockBodies();

        Iterator<byte[]> bodies = bodyList.iterator();
        Iterator<BlockHeaderWrapper> wrappers = sentHeaders.iterator();

        List<Block> blocks = new ArrayList<>(bodyList.size());
        List<BlockHeaderWrapper> coveredHeaders = new ArrayList<>(sentHeaders.size());

        while (bodies.hasNext() && wrappers.hasNext()) {
            BlockHeaderWrapper wrapper = wrappers.next();
            byte[] body = bodies.next();

            Block b = new Block.Builder()
                    .withHeader(wrapper.getHeader())
                    .withBody(body)
                    .create();

            // handle invalid merge
            if (b == null) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to [GET_BLOCK_BODIES], header {} can't be merged with body {}",
                        channel.getPeerIdShort(), wrapper.getHeader(), toHexString(body)
                );
                return null;
            }

            coveredHeaders.add(wrapper);
            blocks.add(b);
        }

        // remove headers covered by response
        sentHeaders.removeAll(coveredHeaders);

        return blocks;
    }

    private boolean isValid(BlockBodiesMessage response) {

        // against best known block,
        // if short sync is in progress there might be a case when
        // peer have no bodies even for blocks with lower number than best known
        if (!syncDone) {

            int expectedCount = 0;
            if (sentHeaders.get(sentHeaders.size() - 1).getNumber() <= bestKnownBlock.getNumber()) {
                expectedCount = sentHeaders.size();
            } else if (sentHeaders.get(0).getNumber() > bestKnownBlock.getNumber()) {
                expectedCount = 0;
            } else {
                for (int i = 0; i < sentHeaders.size(); i++)
                    if (sentHeaders.get(i).getNumber() <= bestKnownBlock.getNumber()) {
                        expectedCount = i;
                    } else {
                        break;
                    }
            }

            if (response.getBlockBodies().size() < expectedCount) {
                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to [GET_BLOCK_BODIES], expected count {}, got {}",
                        channel.getPeerIdShort(), expectedCount, response.getBlockBodies().size()
                );
                return false;
            }
        }

        // check if peer didn't return a body
        // corresponding to the header sent previously
        if (response.getBlockBodies().size() < sentHeaders.size()) {
            BlockHeaderWrapper header = sentHeaders.get(response.getBlockBodies().size());
            if (header.sentBy(channel.getNodeId())) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to [GET_BLOCK_BODIES], body for {} wasn't returned",
                        channel.getPeerIdShort(), header
                );
                return false;
            }
        }

        return true;
    }

    private boolean isValid(BlockHeadersMessage response) {

        List<BlockHeader> headers = response.getBlockHeaders();

        // max headers
        if (headers.size() > headersRequest.getMaxHeaders()) {

            if (logger.isInfoEnabled()) logger.info(
                    "Peer {}: invalid response to {}, exceeds maxHeaders limit, headers count={}",
                    channel.getPeerIdShort(), headersRequest, headers.size()
            );
            return false;
        }

        // emptiness against best known block
        if (headers.isEmpty()) {

            // if we know nothing about bestBlock then it must be initial call after handshake
            if (bestKnownBlock == null) {
                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to initial {}, empty",
                        channel.getPeerIdShort(), headersRequest
                );
                return false;
            }

            if (headersRequest.getBlockHash() == null &&
                    headersRequest.getBlockNumber() <= bestKnownBlock.getNumber()) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to {}, it's empty while bestKnownBlock is {}",
                        channel.getPeerIdShort(), headersRequest, bestKnownBlock
                );
                return false;
            }

            return true;
        }

        // first header
        BlockHeader first = headers.get(0);

        if (headersRequest.getBlockHash() != null) {

            if (headersRequest.getSkipBlocks() == 0) {
                if (!Arrays.equals(headersRequest.getBlockHash(), first.getHash())) {

                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, first header is invalid {}",
                            channel.getPeerIdShort(), headersRequest, first
                    );
                    return false;
                }
            }

        } else {

            long expectedNum = headersRequest.getBlockNumber() + headersRequest.getSkipBlocks();
            if (expectedNum != first.getNumber()) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to {}, first header is invalid {}",
                        channel.getPeerIdShort(), headersRequest, first
                );
                return false;
            }

            // parent of the first block
            // check in long sync only
            if (!syncDone && eldestHash != null) {

                if (!Arrays.equals(eldestHash, first.getParentHash())) {
                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, got parent hash {} for #{}, expected {}",
                            channel.getPeerIdShort(), headersRequest, toHexString(headers.get(0).getParentHash()),
                            headers.get(0).getNumber(), toHexString(eldestHash)
                    );
                    return false;
                }
            }
        }

        // if peer is not in HASH_RETRIEVING state
        // then it must be a response after new block hashes come
        // skip next checks
        if (syncState != HASH_RETRIEVING) return true;

        // numbers and ancestors
        if (headersRequest.isReverse()) {

            for (int i = 1; i < headers.size(); i++) {

                BlockHeader cur = headers.get(i);
                BlockHeader prev = headers.get(i - 1);

                long num = cur.getNumber();
                long expectedNum = prev.getNumber() - 1;

                if (num != expectedNum) {
                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, got #{}, expected #{}",
                            channel.getPeerIdShort(), headersRequest, num, expectedNum
                    );
                    return false;
                }

                if (!Arrays.equals(prev.getParentHash(), cur.getHash())) {
                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, got parent hash {} for #{}, expected {}",
                            channel.getPeerIdShort(), headersRequest, toHexString(prev.getParentHash()),
                            prev.getNumber(), toHexString(cur.getHash())
                            );
                    return false;
                }
            }
        } else {

            for (int i = 1; i < headers.size(); i++) {

                BlockHeader cur = headers.get(i);
                BlockHeader prev = headers.get(i - 1);

                long num = cur.getNumber();
                long expectedNum = prev.getNumber() + 1;

                if (num != expectedNum) {
                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, got #{}, expected #{}",
                            channel.getPeerIdShort(), headersRequest, num, expectedNum
                    );
                    return false;
                }

                if (!Arrays.equals(cur.getParentHash(), prev.getHash())) {
                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, got parent hash {} for #{}, expected {}",
                            channel.getPeerIdShort(), headersRequest, cur.getParentHash(), cur.getNumber(), prev.getHash()
                    );
                    return false;
                }
            }
        }

        return true;
    }

    private void dropConnection() {

        // todo: reduce reputation

        logger.info("Peer {}: is a bad one, drop", channel.getPeerIdShort());

        queue.dropHeaders(channel.getNodeId());
        queue.dropBlocks(channel.getNodeId());

        disconnect(USELESS_PEER);
    }

    /*************************
     *       Logging         *
     *************************/

    @Override
    public void logSyncStats() {
        if(!logger.isInfoEnabled()) {
            return;
        }
        switch (syncState) {
            case BLOCK_RETRIEVING: logger.info(
                    "Peer {}: [ {}, state {}, blocks count {} ]",
                    version,
                    channel.getPeerIdShort(),
                    syncState,
                    syncStats.getBlocksCount()
            );
                break;
            case HASH_RETRIEVING: logger.info(
                    "Peer {}: [ {}, state {}, hashes count {} ]",
                    version,
                    channel.getPeerIdShort(),
                    syncState,
                    syncStats.getHeadersCount()
            );
                break;
            default: logger.info(
                    "Peer {}: [ {}, state {} ]",
                    version,
                    channel.getPeerIdShort(),
                    syncState
            );
        }
    }

    protected enum EthState {
        INIT,
        STATUS_SUCCEEDED,
        STATUS_FAILED
    }
}
