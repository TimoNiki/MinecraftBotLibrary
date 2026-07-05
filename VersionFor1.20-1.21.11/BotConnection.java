import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID; // Добавлено для UUID
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

    // Переменная для отслеживания текущей фазы подключения
    private int connectionState = 0; // 0 - Handshake, 1 - Login, 2 - Configuration, 3 - Play

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

            // 1. HANDSHAKE (Остался прежним, но стейт переводим в LOGIN = 2)
            PacketBuffer handshake = new PacketBuffer();
            handshake.writeVarInt(0x00); // Packet ID
            handshake.writeVarInt(protocol);
            handshake.writeString(ip);
            handshake.writeShort(port);
            handshake.writeVarInt(2); // Переход в режим LOGIN
            sendPacket(handshake.toByteArray());
            connectionState = 1; // Мы в фазе LOGIN

            // 2. LOGIN START (ИЗМЕНЕНО ДЛЯ МАНКРАФТ 1.19.3+)
            PacketBuffer loginStart = new PacketBuffer();
            loginStart.writeVarInt(0x00); // Packet ID
            loginStart.writeString(nickname);
            
            // В новых версиях обязательно передавать UUID игрока (генерируем оффлайн-UUID из ника)
            UUID playerUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nickname).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            loginStart.writeLong(playerUuid.getMostSignificantBits());
            loginStart.writeLong(playerUuid.getLeastSignificantBits());
            
            sendPacket(loginStart.toByteArray());

            logger.accept("[+] " + nickname + " начинает авторизацию...");

            // 3. СЕТЕВОЙ ЦИКЛ ОБРАБОТКИ ПАКЕТОВ
            while (running && !socket.isClosed()) {
                int length = PacketBuffer.readVarInt(in);
                
                // Чтобы правильно рассчитать остаток пакета для skipBytes
                int idSizeBefore = in.available();
                int packetId = PacketBuffer.readVarInt(in); 
                int idSizeAfter = in.available();
                int packetIdLength = idSizeBefore - idSizeAfter;

                // Размер тела пакета (без учета самого ID)
                int dataLength = length - packetIdLength;

                // --- ФАЗА LOGIN ---
                if (connectionState == 1) {
                    if (packetId == 0x02) { // Login Success (Успешный вход)
                        logger.accept("[+] " + nickname + " успешно авторизовался.");
                        // В версиях 1.20.2+ после Login Success игра переходит в режим CONFIGURATION (2)
                        // В старых версиях (до 1.20.2) сразу переходила в PLAY (3)
                        connectionState = (protocol >= 764) ? 2 : 3; 
                        in.skipBytes(dataLength);
                        continue;
                    }
                    if (packetId == 0x00) { // Disconnect (Кик во время логина)
                        String reason = in.readUTF(); // Либо чтение строки
                        logger.accept("[-] " + nickname + " кикнут: " + reason);
                        break;
                    }
                    if (packetId == 0x01) { // Encryption Request (Сервер лицензионный)
                        logger.accept("[-] " + nickname + ": Ошибка! Сервер требует лицензию (Online Mode).");
                        break;
                    }
                }

                // --- ФАЗА CONFIGURATION (Для 1.20.2+) ---
                if (connectionState == 2) {
                    if (packetId == 0x03) { // Finish Configuration от сервера
                        // Отвечаем серверу, что конфигурацию приняли (Serverbound Finish Configuration)
                        PacketBuffer finishAck = new PacketBuffer();
                        finishAck.writeVarInt(0x02); // ID пакета ответа на конфигурацию
                        sendPacket(finishAck.toByteArray());
                        
                        connectionState = 3; // Переходим в финальную игру (PLAY)
                        logger.accept("[+] " + nickname + " завершил настройку и вошел в мир!");
                        in.skipBytes(dataLength);
                        continue;
                    }
                    
                    // Обработка Keep Alive внутри фазы конфигурации (ID обычно 0x04)
                    if (packetId == 0x04 && dataLength == 8) {
                        long keepAliveId = in.readLong();
                        PacketBuffer respond = new PacketBuffer();
                        respond.writeVarInt(0x04); // Отвечаем тем же ID
                        respond.writeLong(keepAliveId);
                        sendPacket(respond.toByteArray());
                        continue;
                    }
                }

                // --- ФАЗА PLAY (Сама игра) ---
                if (connectionState == 3) {
                    // Универсальный обработчик Keep Alive. Пакет всегда содержит только 1 лонг (8 байт).
                    // На разных версиях ID пакета прыгает от 0x1F до 0x26. Проверяем по длине данных!
                    if (dataLength == 8) { 
                        long keepAliveId = in.readLong(); 
                        
                        PacketBuffer respond = new PacketBuffer();
                        respond.writeVarInt(packetId); // Отвечаем серверу с тем же ID, какой он прислал
                        respond.writeLong(keepAliveId);
                        sendPacket(respond.toByteArray());
                        continue;
                    }
                }

                // Пропускаем все остальные пакеты (чанки, чат, движения), которые боту не интересны
                if (dataLength > 0) {
                    in.skipBytes(dataLength); 
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
