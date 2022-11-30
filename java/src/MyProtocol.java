import client.*;

import java.util.*;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Timer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MyProtocol {

    private static String SERVER_IP = "netsys2.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5400;

    private static int myID = 0; // fixed ID
    private String[] myIdArray = { "Maika", "Chris", "Patrick", "Andrei" };
    private int tokenPassing = myID;
    private static int shift = 8;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    private boolean acked = false;

    private Timer timer;


    public MyProtocol(String server_ip, int server_port, int frequency) {
        receivedQueue = new LinkedBlockingQueue<>();
        sendingQueue = new LinkedBlockingQueue<>();

        Client c = new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue);
        //Encryption enc = new Encryption(shift);

        new receiveThread(receivedQueue).start();

        try {
            ByteBuffer temp = ByteBuffer.allocate(64);
            int sequenceNumber = 0;
            boolean lastMessageFlag = false;
            int offset = 4;
            Scanner input = new Scanner(System.in);
            while (true) {
                boolean toUser = false;
                while (!toUser) {

                    System.out.print("Who would you like to send your message to? [Maika, Chris, Patrick, Andrei] ");
                    String name = input.nextLine();
                    for (int h = 0; h < myIdArray.length; h++) {
                        if (myIdArray[h].equalsIgnoreCase(name)) {
                            int destinationID = h;
                            System.out.print("Enter message: ");
                            int read = System.in.read(temp.array());
                            for (int i = 0; i < read - 2; i += 14) {
                                // header byte 1
                                byte[] message = new byte[16];
                                message[0] = (byte) (myID);
                                message[0] = (byte) ((message[0] << 2) + (byte) (destinationID));
                                message[0] = (byte) ((message[0] << 4) + (byte) (sequenceNumber));
                                sequenceNumber++;

                                // header byte 2
                                if ((i + 14) >= read - 2) {
                                    message[1] = (byte) (tokenPassing + 1 % 4);
                                    tokenPassing = tokenPassing + 1 % 4;
                                    lastMessageFlag = true;
                                    message[1] = (byte) ((message[1] << 1) + (byte) (1));
                                } else {
                                    message[1] = (byte) (tokenPassing);
                                    lastMessageFlag = false;
                                    message[1] = (byte) ((message[1] << 1) + (byte) (0));
                                }

                                if (read - 2 < 14) {
                                    offset = 0;
                                    message[1] = (byte) ((message[1] << 1) + (byte) (0));
                                    message[1] = (byte) ((message[1] << 1) + (byte) (0));
                                } else if (read - 2 < 29) {
                                    offset = 1;
                                    message[1] = (byte) ((message[1] << 1) + (byte) (0));
                                    message[1] = (byte) ((message[1] << 1) + (byte) (1));
                                } else if (read - 2 < 43) {
                                    offset = 2;
                                    message[1] = (byte) ((message[1] << 1) + (byte) (1));
                                    message[1] = (byte) ((message[1] << 1) + (byte) (0));
                                } else if (read - 2 < 57) {
                                    offset = 3;
                                    message[1] = (byte) ((message[1] << 1) + (byte) (1));
                                    message[1] = (byte) ((message[1] << 1) + (byte) (1));
                                } else {
                                    System.out.println("message too long..");
                                }


                                if (!lastMessageFlag) {
                                    for (int j = 0; j < 14; j++) {
                                        message[j + 2] = temp.array()[i + j];
                                    }
                                } else if (lastMessageFlag) {
                                    for (int j = 0; j < read - i - 2; j++) {
                                        message[j + 2] = temp.array()[i + j];
                                    }
                                }
                                ByteBuffer toSend = ByteBuffer.allocate(message.length);
                                if (read < 16) {
                                    toSend.put(message, 0, read);
                                } else {
                                    toSend.put(message, 0, message.length);
                                }

                                Message msg = new Message(MessageType.DATA, toSend);
                                timer = new Timer();
                                timer.scheduleAtFixedRate(new MyTimerTask(msg), 0, 10000);
                                sendingQueue.put(msg);
                                acked = false;

                                // handling ack
                                while (!acked) {
                                    Thread.sleep(500);
                                }
                                timer.cancel();

                            }
                        }
                    }
                    toUser = true;
                }
            }
        } catch (InterruptedException e) {
            System.exit(2);
        } catch (IOException e) {
            System.exit(2);
        }
    }

    public static void main(String args[]) {
        if (args.length == 2) {
            frequency = Integer.parseInt(args[0]);
            myID = Integer.parseInt(args[1]);
        }
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
    }

    private class MyTimerTask extends TimerTask {
        Message msg;

        public MyTimerTask(Message msg) {
            this.msg = msg;
        }

        @Override
        public void run() {
            try {
                sendingQueue.put(msg);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void run() {
            boolean[] ackPacket = { false, false, false, false };
            String[] total = new String[4];
            while (true) {
                try {
                    byte[] ackMessage = new byte[2];
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.DATA) {

                        byte[] receivedMessage = new byte[m.getData().remaining()];
                        m.getData().get(receivedMessage, 0, receivedMessage.length);

                        // ontleding header byte 1
                        int src = (receivedMessage[0] & 0xc0) >> 6;
                        int dest = (receivedMessage[0] & 0x30) >> 4;
                        int seq = (receivedMessage[0] & 0x0f);

                        // ontleding header byte 2
                        tokenPassing = (receivedMessage[1] & 0x18) >> 3;
                        int last = (receivedMessage[1] & 0x04) >> 2;
                        int offset = (receivedMessage[1] & 0x03);
                        int noBytes = (receivedMessage[1] & 0x0f);
                        int ackn = (receivedMessage[1] & 0x20) >> 5;

                        if (src != myID && dest == myID) {
                            // constuction of ack packet
                            ackMessage[0] = receivedMessage[0];
                            ackMessage[1] = (byte) (1);
                            ackMessage[1] = (byte) ((ackMessage[1] << 5) + ((byte) (receivedMessage[1])));

                            // send ack to sendingQueue
                            ByteBuffer toSend = ByteBuffer.allocate(16);
                            toSend.put(ackMessage, 0, ackMessage.length);
                            Message ack = new Message(MessageType.DATA_SHORT, toSend);
                            sendingQueue.put(ack);

                            if (!ackPacket[seq]) {
                                String message = "";
                                for (int j = 0; j < 14; j++) {
                                    message += (char) (receivedMessage[j + 2]);
                                }
                                total[seq] = message;
                                ackPacket[seq] = true;
                            }
                            if (seq == offset) {
                                String totalMessage = "";
                                for (int k = 0; k <= seq; k++) {
                                    totalMessage += total[k];
                                }
                                System.out.println("<<" + myIdArray[src] + ">> " + totalMessage);
                            }

                        } else if (dest != myID && src != myID) {
                            sendingQueue.put(m);
                        }

                    } else if (m.getType() == MessageType.DATA_SHORT) {
                        byte[] receivedAck = new byte[m.getData().remaining()];
                        m.getData().get(receivedAck, 0, receivedAck.length);

                        if ((receivedAck[1] & 0x20) != (byte) (0) && (receivedAck[0] & 0xc0) == (byte) (myID)) {
                            System.out.println("ack ontvangen");
                            timer.cancel();
                            acked = true;
                        } else if ((receivedAck[1] & 0x20) != (byte) 0 && (receivedAck[0] & 0xc0) != (byte) myID && (receivedAck[0] & 0x30) >> 4 != (byte) myID) {
                            sendingQueue.put(m);
                        }

                    }
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }
}