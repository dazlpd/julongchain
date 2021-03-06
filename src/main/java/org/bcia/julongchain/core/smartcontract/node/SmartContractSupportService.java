/**
 * Copyright Dingxuan. All Rights Reserved.
 * <p>
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * <p>
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bcia.julongchain.core.smartcontract.node;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bcia.julongchain.common.exception.LedgerException;
import org.bcia.julongchain.common.exception.SmartContractException;
import org.bcia.julongchain.common.ledger.IResultsIterator;
import org.bcia.julongchain.common.ledger.blkstorage.BlockStorage;
import org.bcia.julongchain.common.ledger.blkstorage.IBlockStore;
import org.bcia.julongchain.common.ledger.blkstorage.IndexConfig;
import org.bcia.julongchain.common.ledger.blkstorage.fsblkstorage.Config;
import org.bcia.julongchain.common.ledger.blkstorage.fsblkstorage.FsBlockStoreProvider;
import org.bcia.julongchain.core.ledger.INodeLedger;
import org.bcia.julongchain.core.ledger.ITxSimulator;
import org.bcia.julongchain.core.ledger.kvledger.history.IHistoryQueryExecutor;
import org.bcia.julongchain.core.ledger.kvledger.history.historydb.HistoryLevelDBProvider;
import org.bcia.julongchain.core.ledger.kvledger.history.historydb.IHistoryDB;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.statedb.QueryResult;
import org.bcia.julongchain.core.ledger.kvledger.txmgmt.statedb.VersionedKV;
import org.bcia.julongchain.core.ledger.ledgerconfig.LedgerConfig;
import org.bcia.julongchain.core.node.util.NodeUtils;
import org.bcia.julongchain.core.smartcontract.client.SmartContractSupportClient;
import org.bcia.julongchain.protos.common.Common;
import org.bcia.julongchain.protos.ledger.queryresult.KvQueryResult;
import org.bcia.julongchain.protos.ledger.rwset.kvrwset.KvRwset;
import org.bcia.julongchain.protos.node.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.bcia.julongchain.core.smartcontract.node.SmartContractRunningUtil.*;
import static org.bcia.julongchain.core.smartcontract.node.TransactionRunningUtil.*;
import static org.bcia.julongchain.protos.node.SmartContractShim.SmartContractMessage;

/**
 * 智能合约service，负责接收和处理gRPC消息
 *
 * @author wanliangbing
 * @date 2018/4/17
 * @company Dingxuan
 */
public class SmartContractSupportService
        extends SmartContractSupportGrpc.SmartContractSupportImplBase {

    private static Log logger = LogFactory.getLog(SmartContractSupportService.class);

    /**
     * 以smartContractId为key,保存gRPC客户端
     */
    public static Map<String, StreamObserver<SmartContractMessage>>
            smartContractIdAndStreamObserverMap =
            Collections.synchronizedMap(new HashMap<String, StreamObserver<SmartContractMessage>>());

    private static Map<String, LinkedBlockingQueue> txIdAndQueueMap = new HashMap<String, LinkedBlockingQueue>();

    /**
     * 处理智能合约register信息（命令）
     *
     * @param message        智能合约发送过来的信息（命令）
     * @param streamObserver 智能合约gRPC通道
     */
    private void handleRegister(
            SmartContractMessage message, StreamObserver<SmartContractMessage> streamObserver) {
        try {
            // 保存智能合约编号
            saveSmartContractStreamObserver(message, streamObserver);

            String smartContractId = getSmartContractId(message);

            // 发送注册成功命令
            SmartContractMessage responseMessage =
                    SmartContractMessage.newBuilder().setType(SmartContractMessage.Type.REGISTERED).build();
            streamObserver.onNext(responseMessage);

            // 发送ready命令
            responseMessage =
                    SmartContractMessage.newBuilder().setType(SmartContractMessage.Type.READY).build();
            streamObserver.onNext(responseMessage);

            // 发送init命令
            if (BooleanUtils.isTrue(
                    SmartContractSupportClient.checkSystemSmartContract(smartContractId))) {
                Common.GroupHeader groupHeader =
                        Common.GroupHeader.newBuilder()
                                .setType(Common.HeaderType.ENDORSER_TRANSACTION.getNumber())
                                .build();
                Common.Header header =
                        Common.Header.newBuilder().setGroupHeader(groupHeader.toByteString()).build();
                ProposalPackage.Proposal proposal =
                        ProposalPackage.Proposal.newBuilder().setHeader(header.toByteString()).build();
                ProposalPackage.SignedProposal signedProposal =
                        ProposalPackage.SignedProposal.newBuilder()
                                .setProposalBytes(proposal.toByteString())
                                .build();
                SmartContractEventPackage.SmartContractEvent smartContractEvent =
                        SmartContractEventPackage.SmartContractEvent.newBuilder()
                                .setSmartContractId(smartContractId)
                                .build();
                responseMessage =
                        SmartContractMessage.newBuilder()
                                .setType(SmartContractMessage.Type.INIT)
                                .setProposal(signedProposal)
                                .setSmartContractEvent(smartContractEvent)
                                .build();
                streamObserver.onNext(responseMessage);
            }

            // 设置状态ready
            updateSmartContractStatus(smartContractId, SMART_CONTRACT_STATUS_SEND_INIT);

        } catch (InvalidProtocolBufferException e) {
            logger.error(e.getMessage(), e);
            SmartContractMessage smartContractMessage = SmartContractMessage.newBuilder()
                    .setType(SmartContractMessage.Type.ERROR)
                    .build();
            streamObserver.onNext(smartContractMessage);
        }
    }

    @Override
    public StreamObserver<SmartContractMessage> register(
            StreamObserver<SmartContractMessage> responseObserver) {

        return new StreamObserver<SmartContractMessage>() {

            @Override
            public void onNext(SmartContractMessage message) {

                String txId = message.getTxid();
                String groupId = message.getGroupId();
                String smartContractId = getSmartContractIdByTxId(txId);
                if (StringUtils.isEmpty(smartContractId)) {
                    smartContractId = message.getSmartContractEvent().getSmartContractId();
                }

                // 收到error信息
                if (message.getType().equals(SmartContractMessage.Type.ERROR)) {
                    addTxMessage(smartContractId, txId, message);
                    updateSmartContractStatus(smartContractId, SMART_CONTRACT_STATUS_ERROR);
                    updateTxStatus(smartContractId, txId, TX_STATUS_ERROR);
                    handleReceiveCompleteOrErrorMessage(message, txId + "-" + smartContractId);
                    return;
                }

                // 收到register消息
                if (message.getType().equals(SmartContractMessage.Type.REGISTER)) {
                    handleRegister(message, responseObserver);
                    return;
                }

                // 收到complete信息
                if (message.getType().equals(SmartContractMessage.Type.COMPLETED)) {
                    addTxMessage(smartContractId, txId, message);
                    updateSmartContractStatus(smartContractId, SMART_CONTRACT_STATUS_READY);
                    updateTxStatus(smartContractId, txId, TX_STATUS_COMPLETE);
                    handleReceiveCompleteOrErrorMessage(message, txId + "-" + smartContractId);
                    return;
                }

                // 收到keepalive信息
                if (message.getType().equals(SmartContractMessage.Type.KEEPALIVE)) {
                    responseObserver.onNext(message);
                    return;
                }

                // 收到getState信息
                if (message.getType().equals(SmartContractMessage.Type.GET_STATE)) {
                    handleGetState(message, txId, groupId, smartContractId, responseObserver);
                    return;
                }

                if (message.getType().equals(SmartContractMessage.Type.GET_STATE_BY_RANGE)) {
                    handleGetStateByRange(message, txId, groupId, smartContractId, responseObserver);
                    return;
                }


                // 收到putState信息
                if (message.getType().equals(SmartContractMessage.Type.PUT_STATE)) {
                    handlePutState(message, txId, groupId, smartContractId, responseObserver);
                    return;
                }

                // 收到delState信息
                if (message.getType().equals(SmartContractMessage.Type.DEL_STATE)) {
                    handleDelState(message, txId, groupId, smartContractId, responseObserver);
                    return;
                }

                // 收到getHistoryForKey信息
                if (message.getType().equals(SmartContractMessage.Type.GET_HISTORY_FOR_KEY)) {
                    handleGetHistoryForKey(message, txId, groupId, smartContractId, responseObserver);
                    return;
                }

                logger.error("收到错误的消息类型，请检查参数。");
            }

            @Override
            public void onError(Throwable throwable) {
                logger.error(throwable.getMessage(), throwable);
            }

            @Override
            public void onCompleted() {
                logger.info("SmartContract completed");
            }
        };
    }

    private void handleReceiveCompleteOrErrorMessage(SmartContractMessage message, String txId) {
        if (StringUtils.isEmpty(txId)) {
            return;
        }
        try {
            LinkedBlockingQueue queue = txIdAndQueueMap.get(txId);
            if (queue == null) {
                return;
            }
            queue.put(message);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void handlePutState(SmartContractMessage message, String txId, String groupId, String smartContractId, StreamObserver<SmartContractMessage> responseObserver) {
        SmartContractMessage smartContractMessage = handlePutState(message, txId, groupId, smartContractId);
        responseObserver.onNext(smartContractMessage);
    }

    public SmartContractMessage handlePutState(SmartContractMessage message, String txId, String groupId, String smartContractId) {
        SmartContractMessage.Type type = null;
        try {
            SmartContractShim.PutState putState = SmartContractShim.PutState.parseFrom(message.getPayload());
            INodeLedger nodeLedger = NodeUtils.getLedger(groupId);
            ITxSimulator txSimulator = nodeLedger.newTxSimulator(txId);
            txSimulator.setState(smartContractId, putState.getKey(), putState.getValue().toByteArray());
            type = SmartContractMessage.Type.RESPONSE;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            type = SmartContractMessage.Type.ERROR;
        }
        SmartContractMessage smartContractMessage =
                SmartContractMessage.newBuilder()
                        .mergeFrom(message)
                        .setType(SmartContractMessage.Type.RESPONSE)
                        .setTxid(txId)
                        .setGroupId(groupId)
                        .build();
        return smartContractMessage;
    }

    private void handleDelState(SmartContractMessage message, String txId, String groupId, String smartContractId, StreamObserver<SmartContractMessage> responseObserver) {
        SmartContractMessage responseMessage = handleDelState(message, txId, groupId, smartContractId);
        responseObserver.onNext(responseMessage);
    }

    public SmartContractMessage handleDelState(SmartContractMessage message, String txId, String groupId, String smartContractId) {
        SmartContractMessage.Type type = null;
        try {
            SmartContractShim.DelState delState = SmartContractShim.DelState.parseFrom(message.getPayload());
            INodeLedger nodeLedger = NodeUtils.getLedger(groupId);
            ITxSimulator txSimulator = nodeLedger.newTxSimulator(txId);
            txSimulator.deleteState(groupId, delState.getKey());
            type = SmartContractMessage.Type.RESPONSE;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            type = SmartContractMessage.Type.ERROR;
        }
        SmartContractMessage smartContractMessage =
                SmartContractMessage.newBuilder()
                        .mergeFrom(message)
                        .setType(type)
                        .setTxid(txId)
                        .setGroupId(groupId)
                        .build();
        return smartContractMessage;
    }

    private void handleGetHistoryForKey(SmartContractMessage message, String txId, String groupId, String smartContractId, StreamObserver<SmartContractMessage> responseObserver) {
        SmartContractMessage responseMessage = handleGetHistoryForKey(message, txId, groupId, smartContractId);
        responseObserver.onNext(responseMessage);
    }

    public SmartContractMessage handleGetHistoryForKey(SmartContractMessage message, String txId, String groupId, String smartContractId) {
        SmartContractMessage.Type type = null;
        SmartContractShim.QueryResponse queryResponse = SmartContractShim.QueryResponse.newBuilder().build();
        try {
            SmartContractShim.GetHistoryForKey getHistoryForKey = SmartContractShim.GetHistoryForKey.parseFrom(message.getPayload());
            HistoryLevelDBProvider provider = new HistoryLevelDBProvider();
            IHistoryDB db = provider.getDBHandle(groupId);
            String[] attrsToIndex = {
                    BlockStorage.INDEXABLE_ATTR_BLOCK_HASH,
                    BlockStorage.INDEXABLE_ATTR_BLOCK_NUM,
                    BlockStorage.INDEXABLE_ATTR_TX_ID,
                    BlockStorage.INDEXABLE_ATTR_BLOCK_NUM_TRAN_NUM,
                    BlockStorage.INDEXABLE_ATTR_BLOCK_TX_ID,
                    BlockStorage.INDEXABLE_ATTR_TX_VALIDATION_CODE
            };
            IndexConfig indexConfig = new IndexConfig(attrsToIndex);
            FsBlockStoreProvider fsBlockStoreProvider = new FsBlockStoreProvider(new Config(LedgerConfig.getBlockStorePath(), LedgerConfig.getMaxBlockfileSize()), indexConfig);
            IBlockStore blockStore = fsBlockStoreProvider.openBlockStore(groupId);
            IHistoryQueryExecutor hqe = db.newHistoryQueryExecutor(blockStore);
            IResultsIterator iterator = hqe.getHistoryForKey(smartContractId, getHistoryForKey.getKey());
            SmartContractShim.QueryResponse.Builder queryResponseBuilder = SmartContractShim.QueryResponse.newBuilder();
            while (true) {
                QueryResult queryResult = iterator.next();
                if (queryResult == null) {
                    break;
                }
                KvRwset.Version version = (KvRwset.Version) queryResult.getObj();
                queryResponseBuilder.addResults(SmartContractShim.QueryResultBytes.newBuilder().setResultBytes(version.toByteString()).build());
            }
            queryResponse = queryResponseBuilder.build();
            type = SmartContractMessage.Type.RESPONSE;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            type = SmartContractMessage.Type.ERROR;
        }
        SmartContractMessage smartContractMessage =
                SmartContractMessage.newBuilder()
                        .mergeFrom(message)
                        .setType(type)
                        .setTxid(txId)
                        .setGroupId(groupId)
                        .setPayload(queryResponse.toByteString())
                        .build();
        return smartContractMessage;
    }

    private void handleGetStateByRange(SmartContractMessage message, String txId, String groupId, String smartContractId, StreamObserver<SmartContractMessage> responseObserver) {
        SmartContractMessage smartContractMessage = handleGetStateByRange(message, txId, groupId, smartContractId);
        responseObserver.onNext(smartContractMessage);

    }

    public SmartContractMessage handleGetStateByRange(SmartContractMessage message, String txId, String groupId, String smartContractId) {
        try {
            SmartContractShim.GetStateByRange getStateByRange = SmartContractShim.GetStateByRange.parseFrom(message.getPayload());
            String startKey = getStateByRange.getStartKey();
            String endKey = getStateByRange.getEndKey();

            INodeLedger nodeLedger = NodeUtils.getLedger(groupId);
            ITxSimulator txSimulator = nodeLedger.newTxSimulator(txId);
            IResultsIterator iterator = txSimulator.getStateRangeScanIterator(smartContractId, startKey, endKey);

            SmartContractShim.QueryResponse.Builder queryResponseBuilder = SmartContractShim.QueryResponse.newBuilder();

            while (true) {
                QueryResult queryResult = iterator.next();
                if (queryResult == null) {
                    break;
                }
                VersionedKV kv = (VersionedKV) queryResult.getObj();
                String key = kv.getCompositeKey().getKey();
                if(key.compareTo(endKey) >= 0){
                    break;
                }

                String namespace = kv.getCompositeKey().getNamespace();
                byte[] value = kv.getVersionedValue().getValue();

                KvQueryResult.KV kvProto = KvQueryResult.KV.newBuilder().setKey(key).setNamespace(namespace).setValue(ByteString.copyFrom(value)).build();

                queryResponseBuilder.addResults(SmartContractShim.QueryResultBytes.newBuilder().setResultBytes(kvProto.toByteString()).build());
            }


            SmartContractShim.QueryResponse queryResponse = queryResponseBuilder.build();

            SmartContractMessage responseMessage =
                    SmartContractMessage.newBuilder()
                            .mergeFrom(message)
                            .setType(SmartContractMessage.Type.RESPONSE)
                            .setPayload(queryResponse.toByteString())
                            .setTxid(txId)
                            .setGroupId(groupId)
                            .build();

            return responseMessage;

        } catch (Exception e) {
            logger.error(e.getMessage(), e);

            SmartContractMessage responseMessage =
                    SmartContractMessage.newBuilder()
                            .mergeFrom(message)
                            .setType(SmartContractMessage.Type.ERROR)
                            .setTxid(txId)
                            .setGroupId(groupId)
                            .build();
            return responseMessage;
        }
    }

    private void handleGetState(SmartContractMessage message, String txId, String groupId, String smartContractId, StreamObserver<SmartContractMessage> responseObserver) {
        SmartContractMessage smartContractMessage = handleGetState(message, txId, groupId, smartContractId);
        responseObserver.onNext(smartContractMessage);
    }

    public SmartContractMessage handleGetState(SmartContractMessage message, String txId, String groupId, String smartContractId) {
        String key = message.getPayload().toStringUtf8();
        SmartContractMessage smartContractMessage = null;
        try {
            INodeLedger nodeLedger = NodeUtils.getLedger(groupId);
            ITxSimulator txSimulator = nodeLedger.newTxSimulator(txId);
            byte[] worldStateBytes = txSimulator.getState(smartContractId, key);

            if (worldStateBytes == null) {
                worldStateBytes = new byte[]{};
                smartContractMessage =
                        SmartContractMessage.newBuilder()
                                .mergeFrom(message)
                                .setType(SmartContractMessage.Type.RESPONSE)
                                .setPayload(ByteString.copyFrom(worldStateBytes))
                                .setTxid(txId)
                                .setGroupId(groupId)
                                .build();
            } else {
                smartContractMessage =
                        SmartContractMessage.newBuilder()
                                .mergeFrom(message)
                                .setType(SmartContractMessage.Type.RESPONSE)
                                .setPayload(ByteString.copyFrom(worldStateBytes))
                                .setTxid(txId)
                                .setGroupId(groupId)
                                .build();
            }


        } catch (Exception e) {
            smartContractMessage =
                    SmartContractMessage.newBuilder()
                            .mergeFrom(message)
                            .setType(SmartContractMessage.Type.ERROR)
                            .setTxid(txId)
                            .setGroupId(groupId)
                            .build();
        }
        return smartContractMessage;
    }

    /**
     * 保存gRPC客户端
     *
     * @param message        接收到的消息
     * @param streamObserver gRPC客户端
     * @throws InvalidProtocolBufferException
     */
    private void saveSmartContractStreamObserver(
            SmartContractMessage message, StreamObserver<SmartContractMessage> streamObserver)
            throws InvalidProtocolBufferException {
        // 只有注册时才保存
        if (!message.getType().equals(SmartContractMessage.Type.REGISTER)) {
            return;
        }
        // 从message的payload中获取smartContractID
        SmartContractPackage.SmartContractID smartContractID =
                SmartContractPackage.SmartContractID.parseFrom(message.getPayload());
        String name = smartContractID.getName();
        if (name == null || name.length() == 0) {
            return;
        }
        // 保存gRPC客户端
        smartContractIdAndStreamObserverMap.put(name, streamObserver);
        // 设置状态为new
        updateSmartContractStatus(name, SMART_CONTRACT_STATUS_NEW);
        logger.info(
                String.format(
                        "add SmartContract streamObserver: name[%s] streamObserver[%s]",
                        name, streamObserver.toString()));
    }

    /**
     * 发送消息给gRPC客户端
     *
     * @param smartContractId 智能合约编号
     * @param message         消息
     */
    public static void send(String smartContractId, SmartContractMessage message) {
        StreamObserver<SmartContractMessage> streamObserver =
                smartContractIdAndStreamObserverMap.get(smartContractId);
        if (streamObserver == null) {
            logger.info(String.format("no stream observer for %s", smartContractId));
            return;
        }
        streamObserver.onNext(message);
    }

    /**
     * 初始化智能合约
     *
     * @param smartContractId      智能合约编号
     * @param smartContractMessage 发送的消息
     */
    public static void init(String smartContractId, SmartContractMessage smartContractMessage) {
        logger.info("init " + smartContractId);
        // 设置消息的type为INIT
        SmartContractMessage message =
                SmartContractMessage.newBuilder()
                        .mergeFrom(smartContractMessage)
                        .setType(SmartContractMessage.Type.INIT)
                        .build();
        send(smartContractId, message);
    }

    /**
     * invoke智能合约
     *
     * @param smartContractId      智能合约编号
     * @param smartContractMessage 消息
     */
    public synchronized static SmartContractMessage invoke(
            String smartContractId, SmartContractMessage smartContractMessage) throws SmartContractException{
        logger.info("invoke " + smartContractId);

        // 修改消息的type为TRANSACTION
        SmartContractMessage message =
                SmartContractMessage.newBuilder()
                        .mergeFrom(smartContractMessage)
                        // .setType(SmartContractMessage.Type.TRANSACTION)
                        .build();

        updateSmartContractStatus(smartContractId, SMART_CONTRACT_STATUS_BUSY);
        String txId = smartContractMessage.getTxid();
        addTxId(txId, smartContractId);
        updateTxStatus(smartContractId, txId, TX_STATUS_START);

        // 保存
        LinkedBlockingQueue<SmartContractMessage> queue = new LinkedBlockingQueue<SmartContractMessage>();
        txIdAndQueueMap.put(txId + "-" + smartContractId, queue);
        send(smartContractId, message);

        SmartContractMessage receiveMessage = null;
        try {
            receiveMessage = queue.take();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
            throw new SmartContractException(e.getMessage());
        }

        return receiveMessage;
    }

    /**
     * 从message中获取智能合约编号
     *
     * @param message 消息
     * @return
     */
    private String getSmartContractId(SmartContractMessage message) {
        String smartContractIdStr = "";
        try {
            smartContractIdStr = SmartContractPackage.SmartContractID.parseFrom(message.getPayload()).getName();
        } catch (InvalidProtocolBufferException e) {
            logger.error(e.getMessage(), e);
        }
        return smartContractIdStr;
    }

    public static void main(String[] args) throws Exception {
        invoke("mycc", SmartContractMessage.newBuilder().build());
    }

}
