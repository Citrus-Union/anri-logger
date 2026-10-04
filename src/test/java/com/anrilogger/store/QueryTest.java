package com.anrilogger.store;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QueryTest {
    @Test void filtersAndCompoundDuration() {
        Query q=Query.parse("source:Alice object:diamond range:@global world:minecraft:the_nether after:1d2h before:1h","w","d",1,2,3,200000000);
        assertEquals(-1,q.range()); assertEquals("minecraft:diamond",q.object()); assertEquals(106400000,q.after()); assertEquals(196400000,q.before());
    }
    @Test void rejectInvalidInputRatherThanSilentlyBroadeningQuery() {
        for(String filter:new String[]{"typo:alice","range:-1","range:5000","after:garbage","after:9999999999999999999999w","source:a source:b","action:rollback","after:1h before:2h"})
            assertThrows(RuntimeException.class,()->Query.parse(filter,"w","d",0,0,0,200000000),filter);
    }
}
