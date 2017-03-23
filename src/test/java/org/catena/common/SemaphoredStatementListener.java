package org.catena.common;

import java.util.concurrent.Semaphore;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;
import org.catena.client.CatenaStatementListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SemaphoredStatementListener implements CatenaStatementListener {
    private static final Logger log = LoggerFactory.getLogger(SemaphoredStatementListener.class);
    
    private Semaphore semAppended;
    private Semaphore semWithdrawn;
    private Wallet wallet;
    
    public SemaphoredStatementListener(Wallet wallet, Semaphore a, Semaphore w) {
        this.semAppended = a;
        this.semWithdrawn = w;
        this.wallet = wallet;
    }

    @Override
    public void onStatementAppended(CatenaStatement s) {
        if(semAppended != null) {
            Transaction tx = wallet.getTransaction(s.getTxHash());
            
            log.info("New statement received: '{}', \n\ttxid={}, \n\tprev={}",
                    s.getAsString(), s.getTxHash(), tx.getInput(0).getConnectedOutput().getOutPointFor().getHash());
            semAppended.release();
        }
    }

    @Override
    public void onStatementWithdrawn(CatenaStatement s) {
        if(semWithdrawn != null) {
            Transaction tx = wallet.getTransaction(s.getTxHash());
            
            log.info("Old statement withdrawn: '{}', \n\ttxid={}, \n\tprev={}",
                    s.getAsString(), s.getTxHash(), tx.getInput(0).getConnectedOutput().getOutPointFor().getHash());
            semWithdrawn.release();
        }
    }
}