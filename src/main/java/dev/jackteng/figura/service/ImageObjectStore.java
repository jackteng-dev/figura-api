package dev.jackteng.figura.service;
public interface ImageObjectStore {void put(String key,byte[] data,String type);byte[] get(String key);void delete(String key);}
