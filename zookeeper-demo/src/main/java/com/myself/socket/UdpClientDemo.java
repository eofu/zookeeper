package com.myself.socket;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpClientDemo {
	public static void main(String[] args) throws Exception {
		InetAddress address = InetAddress.getByName("localhost");
		byte[] sendData = "Hello".getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, 8080);
		DatagramSocket datagramSocket = new DatagramSocket();
		datagramSocket.send(sendPacket);
	}
}
