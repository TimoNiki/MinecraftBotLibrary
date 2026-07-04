import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

public class BotConnection implements Runnable {
    private final String ip;
    private final int port;
    private final int protocol;
    private final String nickname;
    private final Consumer<String> logger;
    
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private boolean running = true;

    public BotConnection(String ip, int port, int protocol, String nickname, Consumer<String> logger) {
        this.ip = ip;
        this.port = port;
        this.protocol = protocol;
        this.nickname = nickname;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), 5000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            
            PacketBuffer handshake = new PacketBuffer();
            handshake.writeVarInt(0x00);
            handshake.writeVarInt(protocol);
            handshake.writeString(ip);
            handshake.writeShort(port);
            handshake.writeVarInt(2);
            sendPacket(handshake.toByteArray());

            
            PacketBuffer loginStart = new PacketBuffer();
            loginStart.writeVarInt(0x00);
            loginStart.writeString(nickname);
            loginStart.writeBoolean(false); 
            sendPacket(loginStart.toByteArray());

            logger.accept("[+] " + nickname + " подключился к серверу.");

           
            while (running && !socket.isClosed()) {
                int length = PacketBuffer.readVarInt(in);
                int packetId = PacketBuffer.readVarInt(in); 

                
                if (packetId == 0x23 || packetId == 0x21 || packetId == 0x1F) { 
                    long keepAliveId = in.readLong(); 
                    
                    
                    PacketBuffer respond = new PacketBuffer();
                    respond.writeVarInt(packetId); 
                    respond.writeLong(keepAliveId);
                    sendPacket(respond.toByteArray());
                } else {

                    in.skipBytes(length - 1); 
                }
            }

        } catch (Exception e) {
            if (running) {
                logger.accept("[-] " + nickname + " отключен: " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private synchronized void sendPacket(byte[] packetData) throws IOException {
        PacketBuffer lengthBuffer = new PacketBuffer();
        lengthBuffer.writeVarInt(packetData.length);
        out.write(lengthBuffer.toByteArray()); 
        out.write(packetData);                 
        out.flush();
    }

    public synchronized void close() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
