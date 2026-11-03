package cn.normanclub;

import java.util.List;
import org.junit.jupiter.api.Test;
import cn.normanclub.auth.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityBitmapTest {
    @Test void bitmapRebuildsFromPersistentHashesAndCollisionsRemainOnlyCandidates(){
        UserIdentityMapper db=mock(UserIdentityMapper.class);
        String first="00000001000000020000000300000004"+"0".repeat(32);
        // Same four bitmap positions, different exact HMAC: this is a false positive, not a duplicate.
        String collision="00400001004000020040000300400004"+"a".repeat(32);
        when(db.hashes("")).thenReturn(List.of(first));
        IdentityBitmap bitmap=new IdentityBitmap(db);assertTrue(bitmap.mightContain(first));assertTrue(bitmap.mightContain(collision));
        String absent="00000005000000060000000700000008"+"b".repeat(32);assertFalse(bitmap.mightContain(absent));
        bitmap.add(absent);assertTrue(bitmap.mightContain(absent));verify(db,times(1)).hashes("");
        assertTrue(new IdentityBitmap(db).mightContain(first));
    }
}
