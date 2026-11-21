package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** User-selected LAN console. Query power state without waking, pairing or starting Remote Play. */
final class ConsolePresence {
    enum State { AWAKE, STANDBY, UNKNOWN }
    static InetAddress localAddress(String text) throws IOException {
        String[] parts=text.trim().split("\\.",-1);
        if(parts.length!=4) throw new IOException("Enter the local IPv4 address shown in PS5 network settings.");
        byte[] bytes=new byte[4];
        for(int i=0;i<4;i++) {
            if(!parts[i].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[i])>255)
                throw new IOException("Invalid console IPv4 address.");
            bytes[i]=(byte)Integer.parseInt(parts[i]);
        }
        InetAddress address=InetAddress.getByAddress(bytes);
        if(!address.isSiteLocalAddress() || (bytes[3]&255)==255)
            throw new IOException("Use your console’s private LAN address.");
        return address;
    }
    static State parse(String reply) {
        String[] lines=reply.replace("\r","").replace("\u0000","").split("\n");
        if(lines.length<2)return State.UNKNOWN;
        boolean ps5=false;
        for(String line:lines) {
            String[] h=line.split(":",2);
            if(h.length==2 && h[0].trim().equalsIgnoreCase("device-discovery-protocol-version"))
                ps5="00030010".equals(h[1].trim());
        }
        if(!ps5)return State.UNKNOWN;
        String status=lines[0].toUpperCase(Locale.ROOT);
        if(status.startsWith("HTTP/1.1 200 "))return State.AWAKE;
        return status.startsWith("HTTP/1.1 620 ") ? State.STANDBY : State.UNKNOWN;
    }
    static State query(String ip) throws IOException {
        InetAddress address=localAddress(ip);
        byte[] message="SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n\0".getBytes(StandardCharsets.US_ASCII);
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.connect(address,9302);socket.setSoTimeout(1000);
            socket.send(new DatagramPacket(message,message.length));
            DatagramPacket reply=new DatagramPacket(new byte[2048],2048);socket.receive(reply);
            return parse(new String(reply.getData(),0,reply.getLength(),StandardCharsets.US_ASCII));
        } catch(SocketTimeoutException | PortUnreachableException unavailable) {return State.UNKNOWN;}
    }
    static int target(State state,boolean phoneIsHost,boolean enumerated) {
        if(!enumerated)return -1;
        if(phoneIsHost || state==State.STANDBY)return 0;
        return state==State.AWAKE ? 1 : -1;
    }
}
