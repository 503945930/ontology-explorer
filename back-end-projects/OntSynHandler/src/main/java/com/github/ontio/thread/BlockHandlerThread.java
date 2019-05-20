/*
 * Copyright (C) 2018 The ontology Authors
 * This file is part of The ontology library.
 *
 * The ontology is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ontology is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with The ontology.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.github.ontio.thread;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.ontio.OntSdk;
import com.github.ontio.config.ParamsConfig;
import com.github.ontio.mapper.*;
import com.github.ontio.model.common.BatchBlockDto;
import com.github.ontio.model.dao.Current;
import com.github.ontio.model.dao.Oep4TxDetail;
import com.github.ontio.model.dao.TxDetail;
import com.github.ontio.model.dao.TxEventLog;
import com.github.ontio.service.BlockHandleService;
import com.github.ontio.service.CommonService;
import com.github.ontio.utils.ConstantParam;
import com.github.ontio.utils.Helper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component("BlockHandlerThread")
@Scope("prototype")
@EnableTransactionManagement(proxyTargetClass = true)
public class BlockHandlerThread extends Thread {

    private final String CLASS_NAME = this.getClass().getSimpleName();

    private final ParamsConfig paramsConfig;
    private final BlockHandleService blockManagementService;
    private final CurrentMapper currentMapper;
    private final OntidTxDetailMapper ontidTxDetailMapper;
    private final TxDetailMapper txDetailMapper;
    private final Oep4TxDetailMapper oep4TxDetailMapper;
    private final Oep5TxDetailMapper oep5TxDetailMapper;
    private final Oep8TxDetailMapper oep8TxDetailMapper;
    private final TxDetailDailyMapper txDetailDailyMapper;
    private final TxEventLogMapper txEventLogMapper;
    private final Environment env;
    private final CommonService commonService;
    private final BlockMapper blockMapper;

    @Autowired
    public BlockHandlerThread(TxDetailMapper txDetailMapper, ParamsConfig paramsConfig, BlockHandleService blockManagementService, CurrentMapper currentMapper, OntidTxDetailMapper ontidTxDetailMapper, Oep4TxDetailMapper oep4TxDetailMapper, Environment env, Oep5TxDetailMapper oep5TxDetailMapper, Oep8TxDetailMapper oep8TxDetailMapper, TxDetailDailyMapper txDetailDailyMapper, TxEventLogMapper txEventLogMapper, CommonService commonService, BlockMapper blockMapper) {
        this.txDetailMapper = txDetailMapper;
        this.paramsConfig = paramsConfig;
        this.blockManagementService = blockManagementService;
        this.currentMapper = currentMapper;
        this.ontidTxDetailMapper = ontidTxDetailMapper;
        this.oep4TxDetailMapper = oep4TxDetailMapper;
        this.env = env;
        this.oep5TxDetailMapper = oep5TxDetailMapper;
        this.oep8TxDetailMapper = oep8TxDetailMapper;
        this.txDetailDailyMapper = txDetailDailyMapper;
        this.txEventLogMapper = txEventLogMapper;
        this.commonService = commonService;
        this.blockMapper = blockMapper;
    }

    /**
     * BlockHandlerThread
     */
    @Override
    public void run() {
        log.info("========{}.run=======", CLASS_NAME);
        try {
            ConstantParam.MASTERNODE_RESTFULURL = paramsConfig.MASTERNODE_RESTFUL_URL;
            //初始化node列表
            initNodeRestfulList();
            //初始化sdk object
            initSdkService();

            int oneBlockTryTime = 1;
            while (true) {

                int remoteBlockHieght = commonService.getRemoteBlockHeight();
                log.info("######remote blockheight:{}", remoteBlockHieght);

                int dbBlockHeight = currentMapper.selectBlockHeight();
                log.info("######db blockheight:{}", dbBlockHeight);

                //wait for generating block
                if (dbBlockHeight >= remoteBlockHieght) {
                    log.info("+++++++++wait for block+++++++++");
                    try {
                        Thread.sleep(paramsConfig.BLOCK_INTERVAL);
                    } catch (InterruptedException e) {
                        log.error("error...", e);
                        e.printStackTrace();
                    }
                    oneBlockTryTime++;
                    if (oneBlockTryTime >= paramsConfig.NODE_WAITFORBLOCKTIME_MAX) {
                        commonService.switchNode();
                        oneBlockTryTime = 1;
                    }
                    continue;
                }
                oneBlockTryTime = 1;

                //每次删除当前current表height+1的交易，防止上次程序异常退出时，因为多线程事务插入了height+1的交易
                //而current表height未更新，则本次同步再次插入会报主键重复异常
                ontidTxDetailMapper.deleteByHeight(dbBlockHeight + 1);
                txDetailMapper.deleteByHeight(dbBlockHeight + 1);
                txDetailDailyMapper.deleteByHeight(dbBlockHeight + 1);
                oep4TxDetailMapper.deleteByHeight(dbBlockHeight + 1);
                oep5TxDetailMapper.deleteByHeight(dbBlockHeight + 1);
                oep8TxDetailMapper.deleteByHeight(dbBlockHeight + 1);
                txEventLogMapper.deleteByHeight(dbBlockHeight + 1);

                ConstantParam.BATCHBLOCKDTO = new BatchBlockDto();
                ConstantParam.BATCHBLOCK_ONTID_COUNT = 0;
                ConstantParam.BATCHBLOCK_ONTIDTX_COUNT = 0;
                int i = 1;
                long timeBegin = 0;
                //handle blocks and transactions
                for (int tHeight = dbBlockHeight + 1; tHeight <= remoteBlockHieght; tHeight++) {
                    if (i % 50 == 1) {
                        timeBegin = System.currentTimeMillis();
                    }
                    JSONObject blockJson = commonService.getBlockJsonByHeight(tHeight);
                    JSONArray txEventLogArray = commonService.getTxEventLogsByHeight(tHeight);
                    long time1 = System.currentTimeMillis();
                    blockManagementService.handleOneBlock(blockJson, txEventLogArray);
                    long time2 = System.currentTimeMillis();
                    log.info("handle one block {} used time:{}", tHeight, (time2 - time1));
                    //10个区块提交一次
                    if (i % paramsConfig.BATCHINSERT_BLOCK_COUNT == 0) {
                        long timeEnd = System.currentTimeMillis();
                        log.info("batch handle {} block from {} use time:{}", paramsConfig.BATCHINSERT_BLOCK_COUNT, tHeight, (timeEnd - timeBegin));
                        batchInsertData(tHeight, ConstantParam.BATCHBLOCKDTO);
                        long timeEnd2 = System.currentTimeMillis();
                        log.info("batch handle {} block from {} insert to db use time:{}", paramsConfig.BATCHINSERT_BLOCK_COUNT, tHeight, (timeEnd2 - timeEnd));
                        initConstantParam();
                    }
                    i++;
                }
            }

        } catch (Exception e) {
            log.error("Exception occured，Synchronization thread can't work,error ...", e);
        }
    }

    /**
     * 重新初始化全局参数
     */
    private void initConstantParam() {
        ConstantParam.BATCHBLOCKDTO = new BatchBlockDto();
        ConstantParam.BATCHBLOCK_ONTID_COUNT = 0;
        ConstantParam.BATCHBLOCK_ONTIDTX_COUNT = 0;
        ConstantParam.BATCHBLOCK_TX_COUNT = 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public void batchInsertData(int tHeight, BatchBlockDto batchBlockDto) {
        //插入tbl_tx_detail表
        if (Helper.isNotEmptyOrNull(batchBlockDto.getTxDetails()) && batchBlockDto.getTxDetails().size() > 0) {
            int count = batchBlockDto.getTxDetails().size();
            if (count > paramsConfig.BATCHINSERT_SQL_COUNT) {
                for (int j = 0; j <= count / paramsConfig.BATCHINSERT_SQL_COUNT; j++) {
                    List<TxDetail> list = batchBlockDto.getTxDetails().subList(j * paramsConfig.BATCHINSERT_SQL_COUNT, (j + 1) * paramsConfig.BATCHINSERT_SQL_COUNT > count ? count : (j + 1) * paramsConfig.BATCHINSERT_SQL_COUNT);
                    if (list.size() > 0) {
                        txDetailMapper.batchInsert(list);
                    }
                }
            } else {
                txDetailMapper.batchInsert(batchBlockDto.getTxDetails());
            }
        }
        //插入tbl_tx_eventlog表
        if (Helper.isNotEmptyOrNull(batchBlockDto.getTxEventLogs()) && batchBlockDto.getTxEventLogs().size() > 0) {
            int count = batchBlockDto.getTxEventLogs().size();
            if (count > paramsConfig.BATCHINSERT_SQL_COUNT) {
                for (int j = 0; j <= count / paramsConfig.BATCHINSERT_SQL_COUNT; j++) {
                    List<TxEventLog> list = batchBlockDto.getTxEventLogs().subList(j * paramsConfig.BATCHINSERT_SQL_COUNT, (j + 1) * paramsConfig.BATCHINSERT_SQL_COUNT > count ? count : (j + 1) * paramsConfig.BATCHINSERT_SQL_COUNT);
                    if (list.size() > 0) {
                        txEventLogMapper.batchInsert(list);
                    }
                }
            } else {
                txEventLogMapper.batchInsert(batchBlockDto.getTxEventLogs());
            }
        }
        //插入tbl_oep4_tx_detail表
        if (Helper.isNotEmptyOrNull(batchBlockDto.getOep4TxDetails()) && batchBlockDto.getOep4TxDetails().size() > 0) {
            int count = batchBlockDto.getOep4TxDetails().size();
            if (count > paramsConfig.BATCHINSERT_SQL_COUNT) {
                for (int j = 0; j <= count / paramsConfig.BATCHINSERT_SQL_COUNT; j++) {
                    List<Oep4TxDetail> list = batchBlockDto.getOep4TxDetails().subList(j * paramsConfig.BATCHINSERT_SQL_COUNT, (j + 1) * paramsConfig.BATCHINSERT_SQL_COUNT > count ? count : (j + 1) * paramsConfig.BATCHINSERT_SQL_COUNT);
                    if (list.size() > 0) {
                        oep4TxDetailMapper.batchInsert(list);
                    }
                }
            } else {
                txEventLogMapper.batchInsert(batchBlockDto.getTxEventLogs());
            }
        }


        //插入tbl_block表
        if (Helper.isNotEmptyOrNull(batchBlockDto.getBlocks()) && batchBlockDto.getBlocks().size() > 0) {
            blockMapper.batchInsert(batchBlockDto.getBlocks());
        }

        List<Current> currents = currentMapper.selectAll();
        int txCount = currents.get(0).getTxCount();
        int ontIdCount = currents.get(0).getOntidCount();
        int nonOntIdTxCount = currents.get(0).getNonontidTxCount();
        updateCurrent(tHeight, txCount + ConstantParam.BATCHBLOCK_TX_COUNT,
                ontIdCount + ConstantParam.BATCHBLOCK_ONTID_COUNT, nonOntIdTxCount + ConstantParam.BATCHBLOCK_TX_COUNT - ConstantParam.BATCHBLOCK_ONTIDTX_COUNT);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateCurrent(int blockHeight, int txCount, int ontIdTxCount, int nonontIdTxCount) {

        Current current = Current.builder()
                .blockHeight(blockHeight)
                .txCount(txCount)
                .ontidCount(ontIdTxCount)
                .nonontidTxCount(nonontIdTxCount)
                .build();
        currentMapper.update(current);
    }


    /**
     * initialize node list for synchronization thread
     */
    private void initNodeRestfulList() {
        for (int i = 0; i < paramsConfig.NODE_COUNT; i++) {
            ConstantParam.NODE_RESTFULURLLIST.add(env.getProperty("node.restful.url_" + i));
        }
    }

    /**
     * initialize ontology ONT_SDKSERVICE object for synchronizing data
     *
     * @return
     */
    private void initSdkService() {
        OntSdk sdkService = OntSdk.getInstance();
        sdkService.setRestful(ConstantParam.MASTERNODE_RESTFULURL);
        ConstantParam.ONT_SDKSERVICE = sdkService;
    }
}
