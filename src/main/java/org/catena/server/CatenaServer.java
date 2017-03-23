package org.catena.server;

import static com.google.common.base.Preconditions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet.SendResult;
import org.catena.common.CatenaService;
import org.bitcoinj.core.Coin;
import org.catena.common.SimpleWallet;
import org.catena.common.CatenaWalletExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CatenaServer extends CatenaService {
        
    private static final Logger log = LoggerFactory.getLogger(CatenaServer.class);
    public static final int OP_RETURN_MAX_SIZE = 80;
    private static final String BITCOINJ_FILE_PREFIX = "catena-server";
    
    /**
     * We need to store constructor-provided key and chain name here; we'll need these later when issuing statements.
     */
    private ECKey chainKey;
    
    private SimpleWallet wallet;
    private CatenaWalletExtension ext;
    
    /**
     * The root-of-trust TXID, needed if we're fully restarting a server and want to make sure we pick up the Catena
     * chain correctly. Will be useful when deploying multiple mutually-distrusting Catena servers with multisigs.
     */
    private Sha256Hash rootOfTrustTxid;
    
    /**
     * Creates a Catena server from scratch, given a secret key that controls some
     * funds (used to start a chain) and given a chain name.
     * 
     * @param params
     * @param directory
     * @param chainKey
     * @param chainName
     * @throws IOException
     */
    public CatenaServer(NetworkParameters params, File directory,
            ECKey chainKey, Sha256Hash txid) throws IOException {
        this(params, directory);
        
        this.chainKey = chainKey;
        this.rootOfTrustTxid = txid;
    }
    
    /**
     * Creates a Catena server from a previous wallet, which should have either a key that controls some funds or can sign/extend a chain.
     * @param params
     * @param directory
     * @param chainKey
     * @param chainName
     * @throws IOException
     */
    public CatenaServer(NetworkParameters params, File directory) throws IOException {
        super(params, directory, BITCOINJ_FILE_PREFIX, false);
        
        log.info("Creating Catena server in dir: " + directory);
        
        setAutoSave(true);
        setBlockingStartup(true);
    }
    
    /**
     * Sets the fee amount paid per KB to Bitcoin miners whenever a new Catena TXN is issued. 
     */
    public void setFeePerKb(Coin feeAmount) {
        Context old = super.context;
        super.context = new Context(params, old.getEventHorizon(), feeAmount, old.isEnsureMinRequiredFee());
    }
    
    /**
     * Appends a statement to the Catena chain. This function first commits
     * the statement on disk, then schedules it for publication on the 
     * blockchain.
     * 
     * @param statement
     * @throws InsufficientMoneyException
     * 
     * @return
     */
    public Transaction appendStatement(byte[] statement) throws InsufficientMoneyException {
        Transaction tx = wallet.appendStatement(statement);
        
        // Broadcast Catena transaction
        //
        // WARNING: Sending the TX this way by calling sendCatenaTxOfflline
        // (it used to be Wallet::sendCoinsOffline but we subclassed Wallet) and then
        // peerGroup().broadcastTransaction() might not work in future version of
        // bitcoinj
        final SendResult result = new SendResult();
        result.tx = tx;
        result.broadcast = peerGroup().broadcastTransaction(tx);
        result.broadcastComplete = result.broadcast.future();
                
        log.trace("Catena server TX (" + tx.getHash() + ") for '" + statement + "' after sending: " + tx);
        return tx;
    }
    
    /**
     * This is called right before the blockchain sync starts, so the wallet 
     * and the peer group are initialized. 
     * I think previous TXs and keys from the wallet are also available by this point.
     * 
     * Here we set our wallet's SK 
     */
    protected void onSetupCompleted() {
        wallet = getCatenaWallet();
        ext = wallet.getCatenaExtension();
        List<ECKey> importedKeys = wallet.getImportedKeys();
        List<Address> watchedAddrs = wallet.getWatchedAddresses();
        //log.debug("Wallet keys: " + importedKeys);
        
        // If there are no imported keys, then we are creating the server
        // for the first time with fresh funds for starting a new chain.
        if(importedKeys.isEmpty()) {
            checkState(chainKey != null);
            
            log.info("Starting with: " + 
                    "addr=" + chainKey.toAddress(params) +
                    ", sk=" + chainKey.getPrivateKeyAsWiF(params));
            
            // NOTE: We'll set the root-of-trust TXID after appendStatement is called for the 1st time, but if this is
            // a reboot of a previous server, we might've been given the root-of-trust TXID
            if(rootOfTrustTxid != null) {
                ext.setRootOfTrustTxid(rootOfTrustTxid);
            }
            // This will trigger a wallet save!
            wallet().importKey(chainKey);
            wallet().addWatchedAddress(chainKey.toAddress(params));
        } else {
            checkState(importedKeys.size() == 1);
            checkState(watchedAddrs.size() == 1);
            
            chainKey = importedKeys.get(0);
            String chainName = ext.hasName() ? ext.getName() : null;
            
            log.info("(Re)starting with: " + 
                    "addr=" + chainKey.toAddress(params) +
                    ", sk=" + chainKey.getPrivateKeyAsWiF(params) +
                    (chainName != null ? ", name=" + chainName : ", no chain name yet"));
        }
    }
    
    /**
     * Returns our own CatenaWallet, a customized Wallet subclass.
     * @return
     */
    @Override
    public SimpleWallet getCatenaWallet() {
        return SimpleWallet.castWallet(wallet());
    }
}
