package org.catena.common;

import java.util.concurrent.Semaphore;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.wallet.KeyChainGroup;
import org.catena.client.ClientWallet;

public class SemaphoredWalletFactory extends ClientWallet.Factory {
        
    private Semaphore semAppended, semWithdrawn;

    public SemaphoredWalletFactory(Semaphore semAppended,
            Semaphore semWithdrawn) {
        this.semAppended = semAppended;
        this.semWithdrawn = semWithdrawn;
    }
    
    @Override
    public ClientWallet create(NetworkParameters params, KeyChainGroup keyChainGroup) {

        ClientWallet w = new ClientWallet(params, keyChainGroup);
        w.addStatementListener(new SemaphoredStatementListener(w, semAppended, semWithdrawn));
        return w;
    }
}