package com.readwide.manager.archive;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Boundary grammar tests independent of codec fixtures and native availability. */
public class Rar3ClassicLzBitBoundaryTest {
    @Test public void fileEndConsumesOnlyItsTwoBitsAtPayloadEnd() throws Exception {
        for(int flag:new int[]{0,1}) for(boolean stream:new boolean[]{false,true}) {
            byte[] packed={(byte)flag};
            RarBitInput input=stream ? new RarBitInput(new ByteArrayInputStream(packed),1) : new RarBitInput(packed);
            input.skipBits(6);
            assertEquals(false,invoke(engine(input),"readEndOfBlock"));
            assertEquals(8,input.bitsRead());assertEquals(0,input.remainingBits());
        }
    }

    @Test public void incompleteTwoBitMarkerStillFails() throws Exception {
        RarBitInput input=new RarBitInput(new byte[]{0});input.skipBits(7);
        try {invoke(engine(input),"readEndOfBlock");fail();}
        catch(EOFException expected) {assertEquals(8,input.bitsRead());}
    }

    @Test public void ppmFlagDoesNotRequireSixteenAvailableBitsOrConsumeIt() throws Exception {
        RarBitInput input=new RarBitInput(new ByteArrayInputStream(new byte[]{(byte)0x80}),1);
        try {invoke(engine(input),"readTables");fail();}
        catch(RarArchiveReader.UnsupportedRarFeatureException expected) {
            assertTrue(expected.getMessage().contains("PPMd"));assertEquals(0,input.bitsRead());
        }
    }

    private static Rar3ClassicLzEngine engine(RarBitInput input) throws Exception {
        Constructor<Rar3ClassicLzEngine> constructor=Rar3ClassicLzEngine.class.getDeclaredConstructor(
                RarBitInput.class,RarLzWindow.class,long.class,int[].class,Rar3VmFilter.ProgramState.class);
        constructor.setAccessible(true);
        return constructor.newInstance(input,new RarLzWindow(16,new ByteArrayOutputStream()),0L,null,
                new Rar3VmFilter.ProgramState());
    }

    private static Object invoke(Rar3ClassicLzEngine engine,String method) throws Exception {
        Method target=Rar3ClassicLzEngine.class.getDeclaredMethod(method);target.setAccessible(true);
        try {return target.invoke(engine);}
        catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();
            throw failure;
        }
    }
}
