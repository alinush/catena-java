package org.catena.common;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;

public class TxUtils {
    
    /**
     * Checks if any of the txs in candidates double spends any of the outputs spent by the transaction in tx.
     * (i.e., checks if tx and candidates conflict by spending the same output(s))  
     * 
     * Copied from the wallet class. Need it to check for lies.
     * 
     * @param tx
     * @param candidates
     * @return
     */
    public static Set<Transaction> findDoubleSpendsAgainst(Transaction tx, Map<Sha256Hash, Transaction> candidates) {
        // Coinbase TXs cannot double spend
        if (tx.isCoinBase()) return Sets.newHashSet();
       
        // Compile a set of outpoints that are spent by tx.
        HashSet<TransactionOutPoint> outpoints = new HashSet<TransactionOutPoint>();
        for (TransactionInput input : tx.getInputs()) {
            outpoints.add(input.getOutpoint());
        }
        
        // Now for each candidate transaction, see if it spends any outpoints as this tx.
        Set<Transaction> doubleSpendTxns = Sets.newHashSet();
        for (Transaction p : candidates.values()) {
            for (TransactionInput input : p.getInputs()) {
                
                TransactionOutPoint outpoint = input.getOutpoint();
                if (outpoints.contains(outpoint)) {
                    // It does, it's a double spend against the candidates, which makes it relevant.
                    doubleSpendTxns.add(p);
                }
            }
        }
        return doubleSpendTxns;
    }
    
    /**
     * Checks if any of the transactions in txs spend the same outputs as the transactions in candidates.
     * 
     * The ordering of the transactions in the collection associated with the output is the following.
     * (1) first, the tx from 'txs'
     * (2) second, any double spends from 'candidates'
     * 
     * @param txs
     * @param candidates
     * @return
     */
    public static Map<TransactionOutPoint, List<Transaction>> findDoubleSpendsAgainst(
            Iterator<Transaction> txsIt, Map<Sha256Hash, Transaction> candidates) {

        // Maps an outpoint to a list of transactions that spend it (ideally, that list should be of size one)
        ArrayListMultimap<TransactionOutPoint, Transaction> outpoints = ArrayListMultimap.create();
        
        while(txsIt.hasNext()) {
            Transaction tx = txsIt.next();
            
            // Coinbase TXs cannot double spend
            if (tx.isCoinBase()) 
                continue;
           
            // Compile a set of outpoints that are spent by tx.            
            for (TransactionInput input : tx.getInputs()) {
                outpoints.put(input.getOutpoint(), tx);
            }
        }
        
        // Now for each candidate transaction, see if it spends any outpoints as this tx.
        for (Transaction tx : candidates.values()) {
            for (TransactionInput input : tx.getInputs()) {
                
                TransactionOutPoint outpoint = input.getOutpoint();
                // We could just call put() directly without calling containsKey() but then we would also detect
                // double spends amongst txs in candidates, which might not be desired by callers.
                if (outpoints.containsKey(outpoint)) {
                    // It does, it's a double spend against the candidates, which makes it relevant.
                    outpoints.put(outpoint, tx);
                }
            }
        }
        
        
        // Now clear <outp, list(tx)> pairs where the sizeof(list) == 1 (i.e., no double spend)
        Map<TransactionOutPoint, List<Transaction>> doubleSpends = Multimaps.asMap(outpoints);
        Iterator<List<Transaction>> it = doubleSpends.values().iterator();
        while(it.hasNext()) {
            int numSpends = it.next().size();
            if(numSpends < 2) {
                it.remove();
            }
        }
        
        return doubleSpends;
    }
    
    public static Map<TransactionOutPoint, List<Transaction>> findDoubleSpendsAmongst(Collection<Transaction> candidates) {
        ArrayListMultimap<TransactionOutPoint, Transaction> map = ArrayListMultimap.create();
        
        // For each outpoint o, spent by each input, of each TX tx, add the
        // "spends" <o, tx> pair to the map.
        for(Transaction tx : candidates) {
            for (TransactionInput input : tx.getInputs()) {
                TransactionOutPoint outpoint = input.getOutpoint();
                
                map.put(outpoint, tx);
            }
        }
        
        // Now clear <o, list(tx)> pairs where the sizeof(list) == 1 (i.e., no double spend)
        Map<TransactionOutPoint, List<Transaction>> doubleSpends = Multimaps.asMap(map);
        Iterator<List<Transaction>> it = doubleSpends.values().iterator();
        while(it.hasNext()) {
            int numSpends = it.next().size();
            if(numSpends < 2) {
                it.remove();
            }
        }
        
        return doubleSpends;
    }
    
    /**
     * Dead TXN, double spends a BUILDING txn or another DEAD txn. Could be in
     * a side chain or unconfirmed (not part of a block).
     * 
     * @param txn
     * @return
     */
    public static boolean isDeadTxn(Transaction txn) {
        return txn.getConfidence().getConfidenceType().equals(ConfidenceType.DEAD);
    }

    /**
     * Confirmed TXN (part of best chain).
     * @param txn
     * @return
     */
    public static boolean isBuildingTxn(Transaction txn) {
        return txn.getConfidence().getConfidenceType().equals(ConfidenceType.BUILDING);
    }

    /**
     * Double spends an earlier PENDING txn.
     * @param txn
     * @return
     */
    public static boolean isPendingInConflictTxn(Transaction txn) {
        return txn.getConfidence().getConfidenceType().equals(ConfidenceType.IN_CONFLICT);
    }

    /**
     * Unconfirmed TXN (not included in block yet). Does not double spend anything.
     * 
     * @param txn
     * @return
     */
    public static boolean isPendingTxn(Transaction txn) {
        return txn.getConfidence().getConfidenceType().equals(ConfidenceType.PENDING);
    }
}
