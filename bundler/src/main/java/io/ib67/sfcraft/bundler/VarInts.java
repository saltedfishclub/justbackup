package io.ib67.sfcraft.bundler;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class VarInts {
    public static void writeVarInt(OutputStream buf, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                buf.write(value);
                return;
            }
            buf.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static void writeVarLong(OutputStream buf, long value) throws IOException {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                buf.write((int) value);
                return;
            }
            buf.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    @SneakyThrows
    public static int readVarInt(InputStream buf) {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = (byte) buf.read();
            value |= (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) {
                break;
            }

            position += 7;

            if (position >= 32) {
                throw new RuntimeException("VarInt is too big");
            }
        }

        return value;
    }

    @SneakyThrows
    public static long readVarLong(InputStream buf) {
        long value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = (byte) buf.read();
            value |= (long) (currentByte & 0x7F) << position;

            if ((currentByte & 0x80) == 0) {
                break;
            }

            position += 7;

            if (position >= 64) {
                throw new RuntimeException("VarLong is too big");
            }
        }

        return value;
    }
}
