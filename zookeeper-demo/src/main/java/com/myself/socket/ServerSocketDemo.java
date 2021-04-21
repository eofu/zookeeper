package com.myself.socket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerSocketDemo {
	public static void main(String[] args) {
		ServerSocket serverSocket = null;
		BufferedReader bufferedReader = null;
		try {
			serverSocket = new ServerSocket(8081);
			Socket socket = serverSocket.accept();// 等待链接
			// 获得输入流
			bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			System.out.println(bufferedReader.readLine());
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			if (serverSocket != null) {
				try {
					serverSocket.close();
				} catch (IOException e) {

				}
			}
		}


	}
}
