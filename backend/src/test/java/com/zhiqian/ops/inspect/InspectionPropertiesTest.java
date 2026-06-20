package com.zhiqian.ops.inspect;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * InspectionProperties getter/setter 全覆盖测试。
 */
class InspectionPropertiesTest {

    @Test
    void defaults() {
        InspectionProperties p = new InspectionProperties();
        assertFalse(p.isScheduledEnabled());
        assertEquals(300000L, p.getIntervalMs());
        assertEquals(80, p.getDiskWarnPercent());
        assertEquals(90, p.getDiskCriticalPercent());
        assertEquals(85, p.getMemWarnPercent());
        assertEquals(95, p.getMemCriticalPercent());
        assertEquals(1.0, p.getLoadWarnPerCore());
        assertEquals(2.0, p.getLoadCriticalPerCore());
        assertEquals(1, p.getZombieWarn());
        assertEquals(5, p.getZombieCritical());
        assertEquals(20, p.getLogErrorWarn());
        assertEquals(100, p.getLogErrorCritical());
        assertEquals(5, p.getLogWindowMinutes());
    }

    @Test
    void settersAndGetters() {
        InspectionProperties p = new InspectionProperties();
        
        p.setScheduledEnabled(true);
        assertTrue(p.isScheduledEnabled());
        
        p.setIntervalMs(60000);
        assertEquals(60000L, p.getIntervalMs());
        
        p.setDiskWarnPercent(70);
        assertEquals(70, p.getDiskWarnPercent());
        
        p.setDiskCriticalPercent(85);
        assertEquals(85, p.getDiskCriticalPercent());
        
        p.setMemWarnPercent(75);
        assertEquals(75, p.getMemWarnPercent());
        
        p.setMemCriticalPercent(90);
        assertEquals(90, p.getMemCriticalPercent());
        
        p.setLoadWarnPerCore(0.8);
        assertEquals(0.8, p.getLoadWarnPerCore());
        
        p.setLoadCriticalPerCore(1.5);
        assertEquals(1.5, p.getLoadCriticalPerCore());
        
        p.setZombieWarn(2);
        assertEquals(2, p.getZombieWarn());
        
        p.setZombieCritical(10);
        assertEquals(10, p.getZombieCritical());
        
        p.setLogErrorWarn(50);
        assertEquals(50, p.getLogErrorWarn());
        
        p.setLogErrorCritical(200);
        assertEquals(200, p.getLogErrorCritical());
        
        p.setLogWindowMinutes(10);
        assertEquals(10, p.getLogWindowMinutes());
    }
}
