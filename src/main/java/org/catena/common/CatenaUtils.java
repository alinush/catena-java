package org.catena.common;

import static com.google.common.base.Preconditions.*;

import java.io.IOException;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletTransaction.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;

public class CatenaUtils {
    static Logger log = LoggerFactory.getLogger(CatenaUtils.class);
    
    public static String summarizeWallet(Wallet wallet) {
        StringBuffer buf = new StringBuffer();
        
        // NOTE: No difference between this getPendingTransactions
        // 1. buf.append(", " + wallet.getTransactionPool(Pool.PENDING).size() + " spent");
        // NOTE: Some difference between these but not sure what yet
        // 1. buf.append(", " + wallet.getTransactionPool(Pool.UNSPENT).size() + " spent");
        // 2. buf.append(", " + wallet.getUnspents().size() + " unspent)");
        buf.append(wallet.getBalance().toFriendlyString());
        buf.append(", " + wallet.getTransactions(true).size() + " tx(s)");
        buf.append(" (" + wallet.getUnspents().size() + " unspent");
        buf.append(", " + wallet.getTransactionPool(Pool.SPENT).size() + " spent");
        buf.append(", " + wallet.getPendingTransactions().size() + " pending");
        buf.append(", " + wallet.getTransactionPool(Pool.DEAD).size() + " dead)");
        
        return buf.toString();
    }
    
    public static Sha256Hash getNextCatenaTxHash(Wallet wallet, Sha256Hash currHash) {
        checkNotNull(currHash);
        Transaction nextTx = getNextCatenaTx(wallet, currHash);
        checkNotNull(nextTx);
        
        return nextTx.getHash();
    }
    
    public static Transaction getNextCatenaTx(Wallet wallet, Sha256Hash currHash) {
        checkNotNull(currHash);
        Transaction currTxn = wallet.getTransaction(currHash);
        checkNotNull(currTxn);
        
        return getNextCatenaTx(wallet, currTxn);
    }
    
    public static Transaction getNextCatenaTx(Wallet wallet, Transaction currTxn) {
        checkNotNull(currTxn);
        
        // Get the TXN after this TXN, if any.
        TransactionInput spentBy = currTxn.getOutput(0).getSpentBy();
        if(spentBy != null && spentBy.getParentTransaction() != null) {
            return spentBy.getParentTransaction();
        } else {
            return null;
        }
    }
    
    public static Sha256Hash getPrevCatenaTxHash(Wallet wallet, Sha256Hash tip) {
        Transaction prevTx = getPrevCatenaTx(wallet, tip);
        
        if(prevTx != null)
            return prevTx.getHash();
        else
            return null;
    }
    
    public static Transaction getPrevCatenaTx(Wallet wallet, Sha256Hash currHash) {
        Transaction currTxn = wallet.getTransaction(currHash);
        
        TransactionOutput spentOutput = currTxn.getInput(0).getConnectedOutput();
        if(spentOutput != null && spentOutput.getParentTransaction() != null) {
            return spentOutput.getParentTransaction();
        } else {
            return null;
        }
        
    }
    
    /**
     * Returns the OP_RETURN data in the specified Catena TX.
     * 
     * @param tx
     * @return
     */
    public static byte[] getCatenaTxData(Transaction tx) {
        Script pubKey = tx.getOutput(1).getScriptPubKey();
        checkState(pubKey.isOpReturn());
        
        ScriptChunk dataChunk = pubKey.getChunks().get(1);
        checkState(dataChunk.isPushData());
        
        return dataChunk.data;
    }
    
    /**
     * Verifies everything about the TX, including signature.
     * 
     * @param tx
     * @param chainAddr
     * @param prevLink
     * @return
     */
    public static boolean isSignedCatenaTx(Transaction tx, Address chainAddr, TransactionOutput prevLink, boolean checkPrevLinkIndex) {
        checkNotNull(tx);
        checkNotNull(chainAddr);
        checkNotNull(prevLink);
        
        return isCatenaTxHelper(tx, true, chainAddr, prevLink, checkPrevLinkIndex);
    }
    
    /**
     * Does not verify TX signature.
     * 
     * @param tx
     * @param chainAddr
     * @param prevLink
     * @param checkPrevLinkIndex	if true, checks that tx spends output #0 of
     * the UTXO in prevLink (set to false when verifying the root-of-trust TX)
     * @return
     */
    public static boolean isCatenaTxNoSig(Transaction tx, Address chainAddr, TransactionOutput prevLink, boolean checkPrevLinkIndex) {
        checkNotNull(tx);
        checkNotNull(chainAddr);
        checkNotNull(prevLink);
        return isCatenaTxHelper(tx, false, chainAddr, prevLink, checkPrevLinkIndex);
    }
    
    public static boolean maybeCatenaTx(Transaction tx) {
        return maybeCatenaTx(tx, null);
    }
    
    public static boolean maybeCatenaTx(Transaction tx, Address chainAddr) {
        checkNotNull(tx);
        return isCatenaTxHelper(tx, false, chainAddr, null, false);
    }

    /**
     * Returns true if the specified tx is a valid Catena TX created from the
     * UTXO in prevLink and signed by chainAddr. 
     * If prevLink is null, then the previous UTXO is not checked.
     * If chainAddr is null, then the chain's address is not checked.
     * 
     * @param tx
     * @param chainAddr
     * @param prevLink
     * @return
     */
    private static boolean isCatenaTxHelper(Transaction tx, boolean verifySig, Address chainAddr, 
            TransactionOutput prevLink, boolean checkPrevLinkIndex) {
        checkNotNull(tx);
        log.trace("Inspecting TX " + tx);
        
        String txid = tx.getHashAsString();
        NetworkParameters params = tx.getParams();
        
        // Check if this TX is properly connected to the previous TX's (unique) UTXO 
        if(prevLink != null) {
            // Check the prev TX's output is #0
            if(checkPrevLinkIndex && prevLink.getIndex() != 0) {
                log.warn("Index of UTXO '" + prevLink.getOutPointFor() + "' was '" + prevLink.getIndex() +   
                        "' but expected index 0 (w.r.t. to txid=" + txid + ")");
                return false;
            }
            
            if(checkConnectedTo(tx, prevLink) == false)
                return false;
                        
            // Check that the address in the previous output matches the one provided to this call
            Address prevLinkAddr = prevLink.getAddressFromP2PKHScript(tx.getParams());
            if(chainAddr != null && prevLinkAddr.equals(chainAddr) == false) {
                log.warn("Address in UTXO '" + prevLink.getOutPointFor() + "' was '" + prevLinkAddr +   
                        "' but expected chain address '" + chainAddr + "' (w.r.t. to txid=" + txid + ")");
                return false;
            }
            
            // Verify the signature on the first input
            if(verifySig) {
                TransactionInput firstInput = tx.getInput(0);
                
                // NOTE: bitcoinj does not verify sigs in SPV mode (see BlockChain class comments)
                // => we have to verify signature on input 0 ourselves.
                
                try {
                    firstInput.verify();
                } catch(ScriptException e) {
                    log.warn("TX '" + txid + "' has invalid signature: " + Throwables.getStackTraceAsString(e));
                    return false;
                } catch(VerificationException e) {
                    log.warn("TX '" + txid + "' has invalid format: " + Throwables.getStackTraceAsString(e));
                    return false;
                } catch(Throwable e) {
                    log.warn("TX '" + txid + "' unknown signature verification error: " + Throwables.getStackTraceAsString(e));
                    return false;
                }
            }
        }
        
        // Make sure we have only one input
        if(tx.getInputs().size() != 1) {
            log.warn("expected only one input in tx '" + txid + "', got " + tx.getInputs().size());
            return false;
        }
        
        // Make sure we have only two outputs (data + next)
        if(tx.getOutputs().size() != 2) {
            log.warn("expected two outputs in tx '" + txid + "' (continuation and OP_RETURN), got " + tx.getOutputs().size());
            return false;
        }
        
        // Make sure chain's address is correct in first output
        Address firstOutputAddr = tx.getOutput(0).getAddressFromP2PKHScript(params);
        if(chainAddr != null && !firstOutputAddr.equals(chainAddr)) {
            log.warn("first output address of '" + txid + "' was '" + firstOutputAddr + "'; expected chain address '" + chainAddr  + "'");
            return false;
        }
        
        // Make sure 2nd output is an OP_RETURN
        Script secondOutput = tx.getOutput(1).getScriptPubKey();
        if(!secondOutput.isOpReturn()) {
            log.warn("second output of '" + txid + "' was supposed to be an OP_RETURN, got '" + secondOutput + "'");
            return false;
        }
        
        // All is well.
        return true;
    }

    /**
     * Checks that the specified TX's first input spends the specified output. 
     * 
     * @param tx
     * @param prevLink
     * @return
     */
    public static boolean checkConnectedTo(Transaction tx,
            TransactionOutput prevLink) {	
        checkNotNull(tx);
        checkNotNull(prevLink);
        TransactionInput input = tx.getInput(0);
        checkNotNull(input);
        
        String txid = tx.getHashAsString();
        
        if(input.getConnectedOutput() == null) {
            log.warn("first input of '" + txid + "' is not connected to anything yet " + 
                    "(either funding TX, receiving TXs out-of-order, or got killed due to double spend)");
            return false;
        }
        
        // Check this TX's first input spends the previous TX's first output
        if(input.getConnectedOutput().equals(prevLink) == false) {		
            log.warn("first input of '" + txid + "' is not connected to expected previous output '" + prevLink.getOutPointFor() + "'");
            return false;
        }
        
        return true;
    }

    public static void generateBlockRegtest() throws IOException,
            InterruptedException {
        Process cmd = Runtime.getRuntime().exec("btc-scripts/cli.sh generate 1");
        boolean success = cmd.waitFor() == 0;
        
        checkState(success, "'generate' JSON-RPC command to bitcoind failed");
        
        log.debug("Successfully generated a block!");
    }
}
