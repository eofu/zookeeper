package com.myself.socket;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientSocketDemo {
	public static void main(String[] args) {
		Socket socket = null;
		try {
			socket = new Socket("127.0.0.1", 8081);
			PrintWriter printWriter = new PrintWriter(socket.getOutputStream(), true);
			printWriter.println("Hello");
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (socket!=null) {
				try {
					socket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
