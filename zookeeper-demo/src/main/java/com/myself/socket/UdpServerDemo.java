package com.myself.socket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class UdpServerDemo {
	public static void main(String[] args) throws IOException {
		// 创建服务，并接受一个数据包
		DatagramSocket datagramSocket = new DatagramSocket(8080);
		byte[] receiveData = new byte[1024];
		DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
		datagramSocket.receive(receivePacket);
	}
}
