package dev.recallguard.qq;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Tiny bounds-checked protobuf wire reader for the small recall schema subset. */
final class ProtoReader {
    static final class Field {
        final int number;
        final int wireType;
        final long varint;
        final byte[] bytes;

        Field(int number, int wireType, long varint, byte[] bytes) {
            this.number = number;
            this.wireType = wireType;
            this.varint = varint;
            this.bytes = bytes;
        }

        String utf8() {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private final byte[] data;
    private int pos;

    ProtoReader(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    List<Field> readAll() {
        ArrayList<Field> fields = new ArrayList<>();
        while (pos < data.length) {
            long tag = readVarint();
            if (tag == 0) throw new IllegalArgumentException("protobuf tag 0");
            int number = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (wire) {
                case 0:
                    fields.add(new Field(number, wire, readVarint(), null));
                    break;
                case 1:
                    require(8);
                    pos += 8;
                    break;
                case 2: {
                    int len = checkedLength(readVarint());
                    require(len);
                    byte[] value = new byte[len];
                    System.arraycopy(data, pos, value, 0, len);
                    pos += len;
                    fields.add(new Field(number, wire, 0, value));
                    break;
                }
                case 5:
                    require(4);
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("unsupported protobuf wire type " + wire);
            }
        }
        return fields;
    }

    private long readVarint() {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            require(1);
            int b = data[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("protobuf varint overflow");
    }

    private int checkedLength(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("protobuf length overflow");
        }
        return (int) value;
    }

    private void require(int count) {
        if (count < 0 || pos + count < pos || pos + count > data.length) {
            throw new IllegalArgumentException("truncated protobuf");
        }
    }

    static byte[] firstBytes(List<Field> fields, int number) {
        for (Field field : fields) {
            if (field.number == number && field.wireType == 2) return field.bytes;
        }
        return null;
    }

    static long firstVarint(List<Field> fields, int number, long fallback) {
        for (Field field : fields) {
            if (field.number == number && field.wireType == 0) return field.varint;
        }
        return fallback;
    }

    static String firstString(List<Field> fields, int number) {
        byte[] value = firstBytes(fields, number);
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    static List<byte[]> repeatedBytes(List<Field> fields, int number) {
        ArrayList<byte[]> result = new ArrayList<>();
        for (Field field : fields) {
            if (field.number == number && field.wireType == 2) result.add(field.bytes);
        }
        return result;
    }
}
