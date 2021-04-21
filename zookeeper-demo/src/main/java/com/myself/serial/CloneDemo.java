package com.myself.serial;

public class CloneDemo {
	public static void main(String[] args) throws CloneNotSupportedException {
		Email email = new Email("content0001");
		Person oldWang = new Person("oldWang");
		oldWang.setEmail(email);

		Person blackOldWang = oldWang.clone();
		blackOldWang.setName("blackOldWang");
		// blackOldWang.getEmail().setContent("content0002");
		blackOldWang.setEmail(new Email("content0002"));
		System.out.println(oldWang);
		System.out.println(blackOldWang);
	}
}
