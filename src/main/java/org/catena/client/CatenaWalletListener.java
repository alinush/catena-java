package org.catena.client;

//import static com.google.common.base.Preconditions.checkNotNull;
//import static com.google.common.base.Preconditions.checkState;

import java.util.List;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.ScriptsChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.bitcoinj.wallet.listeners.WalletReorganizeEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Note on thread-safety from https://bitcoinj.github.io/getting-started-java 
 * 
 * "However, your event listeners do not themselves need to be thread safe as events will queue up and execute in 
 * order. Nor do you have to worry about many other issues that commonly arise when using multi-threaded libraries
 * (for instance, it’s safe to re-enter the library and it’s safe to do blocking operations)"
 */
public class CatenaWalletListener implements WalletCoinsSentEventListener, WalletCoinsReceivedEventListener, 
    ScriptsChangeEventListener, WalletReorganizeEventListener, WalletChangeEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(CatenaWalletListener.class);
    
    /**
     * The Catena ClientWallet we are processing events for
     */
    private ClientWallet wallet;
    
    /**
     * Set to true to notify CatenaStatementListeners about all statement when we reinitialize from a previous wallet.
     */
    boolean notifyDuringWalletReplay = false;

    /**
     * Default constructor.
     */
    public CatenaWalletListener(ClientWallet w) {
        this.wallet = w;
    }

    /**
     * Called whenever a new watched script is added to the wallet.
     *
     * @param isAddingScripts will be true if added scripts, false if removed scripts.
     */
    @Override
    public void onScriptsChanged(Wallet wallet, List<Script> scripts, boolean isAddingScripts) {
        Context.propagate(wallet.getContext());
        
        log.trace("size=" + scripts.size() + ", added=" + isAddingScripts);
    }

    /**
     * As far as I can tell in Wallet.maybeCommitTx and Wallet.receive, the onCoinsSent is called whenever the amount 
     * of coins you own in the  wallet has decreased because of a new TX. However, it will also be called when you 
     * send coins away from your wallet even though you send them to yourself along with coins from others for example, 
     * but your overall balance increases (see Wallet.maybeCommitTx). 
     * 
     * Server receives this when it issues a new statement. Client receives this when the server issues a new statement.
     * 
     * With respect to reorgs, onCoinsSent does not seem to be called when a reorg is happening, or if the TX has been 
     * seen before on a sidechain (see Wallet::receive which calls commitTx and in turn maybeCommitTx).
     */
    public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevbal, Coin newbal) {
        Context.propagate(wallet.getContext());
        
         log.trace("(" + prevbal.toFriendlyString() + " -> " + newbal.toFriendlyString() + ")" + 
                 ", txid=" + tx.getHashAsString() + 
                 ", spends[0]=" + tx.getInput(0).getOutpoint());
    }

    /**
     * As far as I can tell in Wallet.maybeCommitTx and Wallet.receive, the onCoinsReceived is called whenever the
     * amount of coins you own in the wallet has increased because of a new TX. However, it will also be called when
     * you receive any coins in your wallet even though you sent them to yourself for example, and your overall 
     * balance decreases (see Wallet.maybeCommitTx). 
     * 
     * Server receives this when it issues a new statement. Client receives this when the server issues a new statement. 
     */
    public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevbal, Coin newbal) {
        Context.propagate(wallet.getContext());
        
        log.trace("(" + prevbal.toFriendlyString() + " -> " + newbal.toFriendlyString() + ")" + 
                ", txid=" + tx.getHashAsString() +
                ", spends[0]=" + tx.getInput(0).getOutpoint());
    }
    
    /**
     * We use this handler to get the blockchain size before we hear about a fork. (bitcoinj doesn't like to help us
     * figure out much about the fork, like how many blocks were orphaned or how many TXs died.)
     */
    @Override
    public void onWalletChanged(Wallet w) {
        log.trace("Wallet has changed.");
        // Do not proceed updating the Catena chain until we have received and processed the root-of-trust TXN (we
        // cannot reason about a Catena chain without a root-of-trust TXN). 
        // NOTE: Even rebooted wallets might not have received the root of trust TXN.
        Transaction tx = wallet.getTransaction(wallet.getCatenaExtension().getRootOfTrustTxid());
        if(wallet.processRootOfTrustTxn(tx) == false) {
            return;
        }

        // Don't call statement listeners during wallet reboots unless told to do so specifically
        boolean callListeners = true;
        if(wallet.isRebooting()) {
            callListeners = notifyDuringWalletReplay;
            wallet.setRebootingHint(false);
        }
        
        // Update Catena chain: append new statements, withdraw statements if there was a fork, reappend statements 
        // if withdrawn ones are reissued and, of course, check for lies.
        wallet.updateCatenaLog(callListeners);
    }
    
    @Override
    public void onReorganize(Wallet wallet) {
    
    }
}
