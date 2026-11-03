package cn.normanclub.auth;

import java.util.BitSet;
import org.springframework.stereotype.Component;

/** A fixed-size Bitmap Bloom filter over HMAC digests, never over raw identity numbers.
 * Positives require an exact DB lookup; the DB unique index also covers concurrent/other-instance writes.
 * Extra bits from a rolled-back write or closed account are harmless false positives.
 */
@Component
public class IdentityBitmap {
    private static final int BITS=1<<22;
    private final BitSet bits=new BitSet(BITS);
    private final UserIdentityMapper db;
    private boolean ready;
    public IdentityBitmap(UserIdentityMapper db){this.db=db;}
    public synchronized boolean mightContain(String digest){
        if(!ready){String after="";while(true){var batch=db.hashes(after);for(String hash:batch)add(hash);if(batch.size()<1000)break;after=batch.getLast();}ready=true;}
        for(int index:indexes(digest))if(!bits.get(index))return false;return true;
    }
    public synchronized void add(String digest){for(int index:indexes(digest))bits.set(index);}
    private int[] indexes(String digest){
        int[] result=new int[4];for(int i=0;i<4;i++)result[i]=(int)(Long.parseUnsignedLong(digest.substring(i*8,i*8+8),16)&(BITS-1));return result;
    }
}
