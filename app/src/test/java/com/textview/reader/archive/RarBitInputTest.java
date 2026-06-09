package com.textview.reader.archive;

import static org.junit.Assert.assertEquals;

import java.io.EOFException;

import org.junit.Test;

public class RarBitInputTest {
    @Test
    public void readBits_crossesByteBoundary() throws Exception {
        RarBitInput input = new RarBitInput(new byte[] {(byte) 0b1010_1100, (byte) 0b0111_0000});

        assertEquals(0b101, input.readBits(3));
        assertEquals(3L, input.bitsRead());
        assertEquals(0b01100_011, input.readBits(8));
        assertEquals(5, input.remainingBits());
    }

    @Test
    public void peekBits_doesNotConsume() throws Exception {
        RarBitInput input = new RarBitInput(new byte[] {(byte) 0b1110_0000});

        assertEquals(0b111, input.peekBits(3));
        assertEquals(0L, input.bitsRead());
        assertEquals(0b111, input.readBits(3));
    }

    @Test(expected = EOFException.class)
    public void readBits_pastEndThrows() throws Exception {
        new RarBitInput(new byte[] {0}).readBits(9);
    }
}
