package dev.jackteng.figura;

import org.junit.jupiter.api.Test;

public class ThreadLocalTest {

    @Test
    public void testThreadLocalSetAndGet(){
        //提供一個threadlocal對象
        ThreadLocal tl = new ThreadLocal();

        new Thread(()->{
            tl.set("jack");
//            System.out.println(Thread.currentThread().getName()+": "+tl.get());
        },"藍色").start();

        new Thread(()->{
            tl.set("justin");
//            System.out.println(Thread.currentThread().getName()+": "+tl.get());
        },"綠色").start();
    }
}
