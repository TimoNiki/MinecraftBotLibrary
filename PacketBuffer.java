import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class PacketBuffer {
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private final DataOutputStream dos = new DataOutputStream(baos);

    public void writeVarInt(int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                dos.writeByte(value);
                return;
            }
            dos.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) throw new RuntimeException("VarInt is too big");
        } while ((read & 0x80) != 0);
        return result;
    }

    public void writeString(String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        writeVarInt(bytes.length);
        dos.write(bytes);
    }

    public void writeBoolean(boolean value) throws IOException {
        dos.writeBoolean(value);
    }

    public void writeLong(long value) throws IOException {
        dos.writeLong(value);
    }

    public void writeShort(int value) throws IOException {
        dos.writeShort(value);
    }

    public byte[] toByteArray() {
        return baos.toByteArray();
    }
}
