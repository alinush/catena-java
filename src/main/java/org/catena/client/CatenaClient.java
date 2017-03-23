package org.catena.client;

import static com.google.common.base.Preconditions.*;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.WalletProtobufSerializer.WalletFactory;
import org.catena.common.CatenaService;
import org.catena.common.CatenaWalletExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CatenaClient extends CatenaService {
    
    private static final Logger log = LoggerFactory.getLogger(CatenaClient.class);
    protected static final String BITCOINJ_FILE_PREFIX = "catena-client";
        
    /**
     * We need this until bitcoinj allows us to rewind the blockchain and redownload
     * blocks.
     */
    public Address expectedChainAddr;
    
    /**
     * The root of trust TXID.
     */
    protected Sha256Hash rootOfTrustTxid;
    
    /**
     * The number of confirmations needed before a Catena statement is considered
     * valid.
     * TODO: implement (current implementation works for # = 1), maybe by only having BQ contain fully confirmed statments?
     */
    @SuppressWarnings("unused")
    private int numConfirmationsRequired = 6;
    
    /**
     * The bitcoinj wallet of this Catena client.
     */
    private ClientWallet wallet;
    
    /**
     * The Catena wallet has an "extension" which allows it to store the root of trust TXN and the chain name.
     */
    private CatenaWalletExtension ext;
    
    /**
     * Creates a Catena client from scratch given a root-of-trust TXID.
     * 
     * We also pass in the P2PKH address due to bitcoinj limitations which prevent us from restarting the blockchain 
     * download once we've obtained the P2PKH address from the root-of-trust TX. Due to this, if newer blocks are
     * downloaded before the root-of-trust block, the Catena TXs in them won't be redownloaded by bitcoinj. Thus, we 
     * have to give the chain's address so the Bloom filter matches Catena TXs in blocks downloaded before the 
     * root-of-trust block.
     * 
     * @param params
     * @param directory
     * @param txid
     * @param expectedChainAddr
     * @param factory
     *
     * @throws IOException
     */
    public CatenaClient(NetworkParameters params, File directory, Sha256Hash txid, Address expectedChainAddr, 
            WalletFactory factory) throws IOException {
        this(params, directory, factory);
        
        this.expectedChainAddr = expectedChainAddr;
        this.rootOfTrustTxid = txid;
    }
    
    /**
     * Creates a Catena client from a previous Catena wallet.
     *  
     * @param params
     * @param directory
     * @throws IOException
     */
    private CatenaClient(NetworkParameters params, File directory, WalletFactory factory) throws IOException {
        super(params, directory, BITCOINJ_FILE_PREFIX, true);
        super.walletFactory = factory != null ? factory : provideWalletFactory();
        
        log.info("Creating Catena client in dir: " + directory);
        
        setAutoSave(true);
        setBlockingStartup(true);
    }
    
    public WalletFactory provideWalletFactory() {
        return new ClientWallet.Factory();
    }
    
    public void setNumConfirmationsRequired(int num) {
        checkState(num > 0);
        this.numConfirmationsRequired = num;
    }
    
    /**
     * This is called right before the blockchain sync starts, so the wallet and the peer group are initialized. Previous 
     * TXs and keys from the wallet are also available by this point. 
     */
    protected void onSetupCompleted() {
        beforeBlockChainDownload();
    }
    
    /**
     * Sets up the wallet for the Catena client and reads back previous statements if the wallet was restarted.
     */
    private void beforeBlockChainDownload() {
        wallet = getCatenaWallet();
        ext = wallet.getCatenaExtension();
        CatenaWalletListener listener = new CatenaWalletListener(wallet);
        List<Address> watchedAddrs = wallet().getWatchedAddresses();
        

        // If no watched addresses from previously loaded wallet then this is the first time we are "booting", so we 
        // should've been given a txid in the constructor.
        if(watchedAddrs.isEmpty()) {
            wallet.setRebootingHint(false);
            checkState(rootOfTrustTxid != null);

            log.info("Starting new wallet with: " +
                    "\n\ttxid=" + rootOfTrustTxid + 
                    ", \n\tchainAddr=" + expectedChainAddr);
            
            // Here we Bloom filter for the root-of-trust TXID but because bitcoinj is limited and cannot redownload
            // the chain after it matched this TXN, we have to also specify the chain address here to make sure we don't
            // miss relevant blocks and TXs.
            peerGroup().addPeerFilterProvider(new TxidBloomFilterProvider(rootOfTrustTxid));
            wallet.addWatchedAddress(expectedChainAddr);
            ext.setRootOfTrustTxid(rootOfTrustTxid);
            
            // NOTE: Cannot set name of chain here because don't have the root-of-trust TX with the name yet
            
        // Otherwise, we have the chain's address in our watched addresses list which means we can read back the 
        // root-of-trust txid saved in our CatenaWalletExtension.
        } else {
            wallet.setRebootingHint(true);
            
            checkState(watchedAddrs.size() == 1);
            Address chainAddr = watchedAddrs.get(0);
            String chainName = null;
            if(ext.hasName())
                chainName = ext.getName();
            
            rootOfTrustTxid = ext.getRootOfTrustTxid();
            
            log.info("Restarting old wallet with: " +
                    "\n\taddr=" + chainAddr +
                    ", \n\ttxid=" + rootOfTrustTxid +
                    ", \n\t" + (chainName != null ? "name=" + chainName : "no chain name yet") + 
                    ", \n\tnumtxns=" + wallet.getTransactions(false).size());

            // Call onWalletChanged() here to process Catena TXs and initialize the chain
            listener.onWalletChanged(wallet);
        }
        
        // We just want to hear about certain events when debugging!
        wallet().addScriptChangeEventListener(Threading.SAME_THREAD, listener);
        wallet().addCoinsReceivedEventListener(Threading.SAME_THREAD, listener);        
        wallet().addCoinsSentEventListener(Threading.SAME_THREAD, listener);

        // We run these listeners in the same thread as the Wallet listeners because we are concerned with race 
        // conditions: as we handle an old fork, a new one can occur and we would be processing the old one with TX 
        // confidence data from the new fork, possibly putting landing us in an incorrect state.
        wallet().addChangeEventListener(Threading.SAME_THREAD, listener);
        wallet().addReorganizeEventListener(Threading.SAME_THREAD, listener);
        // TODO: can get extra info about reorgs by using chain().addReorganizeListener()
        
        // Wallet is succesfully reloaded with TX from previous invocation of
        // CatenaClient (i.e., wallet survives restarts).
    }
    
    @Override
    public ClientWallet getCatenaWallet() {
        return ClientWallet.castWallet(wallet());
    }
}