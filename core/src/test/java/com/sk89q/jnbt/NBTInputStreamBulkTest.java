package com.sk89q.jnbt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.boydti.fawe.jnbt.NBTStreamer;
import com.boydti.fawe.object.RunnableVal2;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.Test;

public class NBTInputStreamBulkTest {

    private static final int BUFFER_SIZE = 16 * 1024;

    @Test
    public void streamsByteArraysInBoundedChunks() throws IOException {
        for (int length : new int[] {0, 1, BUFFER_SIZE, BUFFER_SIZE + 1, BUFFER_SIZE * 2 + 7}) {
            byte[] expected = values(length);
            RecordingReader reader = new RecordingReader(length);

            readByteArray(expected, reader);

            assertArrayEquals(expected, reader.values);
            assertEquals((length + BUFFER_SIZE - 1) / BUFFER_SIZE, reader.bulkCalls);
            assertEquals(0, reader.scalarCalls);
        }
    }

    @Test
    public void defaultBulkReaderPreservesUnsignedValues() throws IOException {
        byte[] expected = new byte[] {0, 127, (byte) 128, (byte) 255};
        final int[] actual = new int[expected.length];
        NBTStreamer.ByteReader reader = new NBTStreamer.ByteReader() {
            @Override
            public void run(int index, int value) {
                actual[index] = value;
            }
        };

        readByteArray(expected, reader);

        assertArrayEquals(new int[] {0, 127, 128, 255}, actual);
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedByteArrays() throws IOException {
        byte[] encoded = encode(values(BUFFER_SIZE + 1));
        byte[] truncated = new byte[encoded.length - 1];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        readEncoded(truncated, new RecordingReader(BUFFER_SIZE + 1));
    }

    private static void readByteArray(byte[] values, NBTStreamer.ByteReader reader) throws IOException {
        readEncoded(encode(values), reader);
    }

    private static void readEncoded(byte[] encoded, final NBTStreamer.ByteReader reader) throws IOException {
        NBTInputStream input = new NBTInputStream(new ByteArrayInputStream(encoded));
        input.readNamedTagLazy(new RunnableVal2<String, RunnableVal2>() {
            @Override
            public void run(String node, RunnableVal2 ignored) {
                value2 = "Bytes.#".equals(node) ? reader : null;
            }
        });
    }

    private static byte[] encode(byte[] values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(NBTConstants.TYPE_BYTE_ARRAY);
        output.writeUTF("Bytes");
        output.writeInt(values.length);
        output.write(values);
        output.close();
        return bytes.toByteArray();
    }

    private static byte[] values(int length) {
        byte[] values = new byte[length];
        for (int i = 0; i < length; i++) {
            values[i] = (byte) (i * 31);
        }
        return values;
    }

    private static final class RecordingReader extends NBTStreamer.ByteReader {
        private final byte[] values;
        private int bulkCalls;
        private int scalarCalls;

        private RecordingReader(int length) {
            values = new byte[length];
        }

        @Override
        public void run(int index, int value) {
            scalarCalls++;
        }

        @Override
        public void runBulk(int startIndex, byte[] source, int offset, int length) {
            bulkCalls++;
            System.arraycopy(source, offset, values, startIndex, length);
        }
    }
}
