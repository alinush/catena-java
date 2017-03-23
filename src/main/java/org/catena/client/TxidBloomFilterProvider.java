package org.catena.client;

import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.PeerFilterProvider;
import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TxidBloomFilterProvider implements PeerFilterProvider {
    private static final Logger log = LoggerFactory.getLogger(TxidBloomFilterProvider.class);
    
    private Sha256Hash txid;
    
    public TxidBloomFilterProvider(Sha256Hash txid) {
        this.txid = txid;         
    }

    @Override
    public int getBloomFilterElementCount() {
        return 1;	// assuming this is right, since we're only inserting one item in
    }

    @Override
    public BloomFilter getBloomFilter(int size, double falsePositiveRate,
            long nTweak) {
        log.trace("Computed Bloom filter for root-of-trust txid: " + txid);
        
        BloomFilter filter = new BloomFilter(size, falsePositiveRate, nTweak);
        // NOTE: Must reverse the TXID bytes to match them in the Bloom filter.
        //filter.insert(txid.getBytes());
        filter.insert(txid.getReversedBytes());
        return filter;
    }

    @Override
    public boolean isRequiringUpdateAllBloomFilter() {
        // Return false so that when the filter matches a Catena transaction, that
        // transaction's PK in its scriptPubKey is added to the filter. This allows
        // us to keep matching future Catena TXs
        return false;
    }

    @Override
    public long getEarliestKeyCreationTime() {
        // NOTE: Initially, I was not sure what I should return here so I
        // returned Long.MAX_VALUE.
        // Then, I realized that this is the timestamp after which bitcoinj 
        // actually starts downloading filtered and unfiltered block bodies.
        // So, if I set this to Long.MAX_VALUE, it won't download any TXs in
        // the blocks. As a result, I was never matching the TX with this filter.
        // (This can be optimized to minimize the # of downloaded blocks, but 
        //  for now I set it to 0 to be safe.)
        return 1;
    }
    
    @Override
    public void beginBloomFilterCalculation() {
    }

    @Override
    public void endBloomFilterCalculation() {		
    }

}
