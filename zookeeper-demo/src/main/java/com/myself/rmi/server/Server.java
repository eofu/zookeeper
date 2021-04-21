package com.myself.rmi.server;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;

public class Server {
	public static void main(String[] args) {
		try {
			Service service = new ServiceImpl();
			LocateRegistry.createRegistry(1099);
			Naming.rebind("rmi://127.0.0.1/hello",service);
			System.out.println("servcer started.");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
