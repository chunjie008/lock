package com.ycnote.lock;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author youchao
 * 单元测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class LockApplicationTests {
	/**
	 * 链接客户端
	 */
	@Autowired
	CuratorFramework curatorFramework;
	/**
	 * 分布式锁路径
	 */
	String lockPath = "/lock/test";

	/**
	 * 测试分布式可重入排它锁
	 * @throws Exception
	 */
	@Test
	public void testInterProcessMutex() throws Exception {
		//传入链接客户端和锁路径
		InterProcessMutex lock = new InterProcessMutex(curatorFramework, lockPath);
		int availableProcessors = Runtime.getRuntime().availableProcessors();
		log.info("availableProcessors:"+availableProcessors);
		//建立一个线程池
		Executor executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		LockTask task1 = new LockTask(lock,30);
		LockTask task2 = new LockTask(lock,2);
		LockTask task3 = new LockTask(lock,7);
		//启动子线程
		executor.execute(task1);
		executor.execute(task2);
		executor.execute(task3);
		//防止主线程结束
		Thread.sleep(Integer.MAX_VALUE);
	}
	/*
		分布式锁测试线程
	 */
	class LockTask implements Runnable {

		InterProcessMutex lock;

		int waitTime;

		public LockTask(InterProcessMutex lock,int waitTime) {
			this.lock = lock;
			this.waitTime = waitTime;
		}

		@Override
		public void run() {
			//尝试获取锁
			try {
				if (lock.acquire(waitTime, TimeUnit.SECONDS) )
                {
					log.info(Thread.currentThread().getName()+":get the lock for "+lockPath);
					Thread.sleep(5000);
                }
			} catch (Exception e) {
				log.error("get Lock error",e);
			}finally {
				try {
					//释放分布式锁
					lock.release();
					log.info(Thread.currentThread().getName()+":release the lock for "+lockPath);
				} catch (Exception e) {
					log.info(Thread.currentThread().getName()+":release the lock for "+lockPath+" error");
				}
			}
		}
	}




}
