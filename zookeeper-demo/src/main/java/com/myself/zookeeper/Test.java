package com.myself.zookeeper;

public class Test {
	public static void main(String[] args) {
		String str = "s";
		String str2 = "s";

		System.out.println(str==str2);
		System.out.println(str);
		System.out.println(str.hashCode()==str2.hashCode());
		System.out.println(str.equals(str2));
	}
}
