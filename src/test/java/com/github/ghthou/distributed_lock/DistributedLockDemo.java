package com.github.ghthou.distributed_lock;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.apache.curator.framework.recipes.locks.InterProcessMultiLock;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2;
import org.apache.curator.framework.recipes.locks.Lease;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingCluster;
import org.apache.curator.test.TestingServer;
import org.apache.curator.utils.CloseableUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 分布式锁演示说明
 */
public class DistributedLockDemo {

	// zookeeper 锁节点路径,分布式锁的相关操作都是在这个节点上进行
	private final String lockPath = "/distributed-lock";

	// zookeeper
	// 服务地址,单机格式为:(127.0.0.1:2181),集群格式为:(127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183)
	private String connectString;
	// Curator 客户端重试策略
	private RetryPolicy retry;
	// Curator 客户端对象
	private CuratorFramework client;
	// client2 用户模拟其他客户端
	private CuratorFramework client2;

	private TestingServer testingServer;
	private TestingCluster testingCluster;

	// 内存 zookeeper 服务器(单个),用于测试,也可以选择使用外部服务器,如 127.0.0.1:2181
	// 同时提供 TestingCluster,用于测试集群

	// 初始化资源
	@Before
	public void init() throws Exception {

		// 创建一个测试 zookeeper 服务器
		testingServer = new TestingServer();
		// 获取该服务器的链接地址
		connectString = testingServer.getConnectString();
		System.out.println(connectString);

		// 创建一个集群测试 zookeeper 服务器
		// testingCluster = new TestingCluster(3);
		// 获取该服务器的链接地址
		// connectString = testingCluster.getConnectString();

		// connectString = "127.0.0.1:2181";
		// 重试策略
		// 初始休眠时间为 1000ms,最大重试次数为3
		retry = new ExponentialBackoffRetry(1000, 3);
		// 创建一个客户端 60000(ms)为 session 超时时间,15000(ms)为链接超时时间
		client = CuratorFrameworkFactory.newClient(connectString, 60000, 15000, retry);
		client2 = CuratorFrameworkFactory.newClient(connectString, 60000, 15000, retry);
		// 创建会话
		client.start();
		client2.start();
	}

	// 释放资源
	@After
	public void close() {
		CloseableUtils.closeQuietly(client);
		CloseableUtils.closeQuietly(testingServer);
		CloseableUtils.closeQuietly(testingCluster);
	}

	// 共享锁（不可重入 InterProcessSemaphoreMutex）
	@Test
	public void sharedLock() throws Exception {
		// 创建共享锁
		InterProcessLock lock = new InterProcessSemaphoreMutex(client, lockPath);
		// lock2 用于模拟其他客户端
		InterProcessLock lock2 = new InterProcessSemaphoreMutex(client2, lockPath);

		// 获取锁对象
		lock.acquire();

		// 测试是否可以重入
		// 超时获取锁对象(第一个参数为时间,第二个参数为时间单位),因为锁已经被获取,所以返回 false
		System.out.println("lock获取锁=" + lock.acquire(2, TimeUnit.SECONDS));
		System.out.println("lock2获取锁=" + lock2.acquire(2, TimeUnit.SECONDS));
		// 释放锁
		lock.release();
		System.out.println("lock释放锁");
		// lock2 尝试获取锁成功,因为锁已经被释放
		System.out.println("lock2获取锁=" + lock2.acquire(2, TimeUnit.SECONDS));
		System.out.println("lock获取锁=" + lock.acquire(2, TimeUnit.SECONDS));
		lock2.release();
	}

	// 重入锁（InterProcessMutex 可重入）
	@Test
	public void sharedReentrantLock() throws Exception {
		// 创建可重入锁
		InterProcessLock lock = new InterProcessMutex(client, lockPath);
		// lock2 用于模拟其他客户端
		InterProcessLock lock2 = new InterProcessMutex(client2, lockPath);
		// lock 获取锁
		lock.acquire();
		try {
			// lock 第二次获取锁
			System.out.println("lock获取锁=" + lock.acquire(2, TimeUnit.SECONDS));
			// lock.acquire();

			try {
				// lock2 超时获取锁,因为锁已经被 lock 客户端占用,所以获取失败,需要等 lock 释放
				System.out.println("lock2获取锁=" + lock2.acquire(2, TimeUnit.SECONDS));
			} finally {
				System.out.println("lock第一次释放锁");
				lock.release();
			}
		} finally {
			// 重入锁获取与释放需要一一对应,如果获取2次,释放1次,那么该锁依然是被占用,如果将下面这行代码注释,那么会发现下面的 lock2
			// 获取锁失败
			System.out.println("lock第二次释放锁");
			lock.release();
		}
		// 在 lock 释放后,lock2 能够获取锁
		System.out.println("lock2获取锁=" + lock2.acquire(2, TimeUnit.SECONDS));
		lock2.release();
	}

	// 多重锁
	@Test
	public void multiLock() throws Exception {
		// 可重入锁
		InterProcessLock interProcessLock1 = new InterProcessMutex(client, lockPath);
		// 不可重入锁
		InterProcessLock interProcessLock2 = new InterProcessSemaphoreMutex(client2, lockPath);
		// 创建多重锁对象
		InterProcessLock locks = new InterProcessMultiLock(Arrays.asList(interProcessLock1, interProcessLock2));
		// 获取参数集合中的所有锁
		locks.acquire();

		// 因为存在一个不可重入锁,所以整个 InterProcessMultiLock 不可重入
		System.out.println("locks获取锁=" + locks.acquire(2, TimeUnit.SECONDS));
		// interProcessLock1 是可重入锁,所以可以继续获取锁
		System.out.println("interProcessLock1获取锁=" + interProcessLock1.acquire(2, TimeUnit.SECONDS));
		// interProcessLock2 是不可重入锁,所以获取锁失败
		System.out.println("interProcessLock2获取锁=" + interProcessLock2.acquire(2, TimeUnit.SECONDS));

		// 释放参数集合中的所有锁
		locks.release();
		System.out.println("locks释放锁");
		
		// interProcessLock2 中的锁已经释放,所以可以获取
		System.out.println("interProcessLock2获取锁=" + interProcessLock2.acquire(2, TimeUnit.SECONDS));

	}

	// 读写锁(InterProcessReadWriteLock 可重入)
	@Test
	public void sharedReentrantReadWriteLock() throws Exception {
		// 创建读写锁对象,因 curator 的实现原理,该锁是公平的
		InterProcessReadWriteLock lock = new InterProcessReadWriteLock(client, lockPath);
		// lock2 用于模拟其他客户端
		InterProcessReadWriteLock lock2 = new InterProcessReadWriteLock(client2, lockPath);
		// 使用 lock 模拟读操作
		// 使用 lock2 模拟写操作
		// 获取读锁(使用InterProcessMutex实现,所以是可以重入的)
		InterProcessLock readLock = lock.readLock();
		// 获取写锁(使用InterProcessMutex实现,所以是可以重入的)
		InterProcessLock writeLock = lock2.writeLock();

		/**
		 * 读写锁测试对象
		 */
		class ReadWriteLockTest {
			// 测试数据变更字段
			private Integer testData = 0;
			private Set<Thread> threadSet = new HashSet<>();

			// 写入数据
			private void write() throws Exception {
				writeLock.acquire();
				try {
					Thread.sleep(10);
					testData++;
					System.out.println("写入数据\t" + testData);
				} finally {
					writeLock.release();
				}
			}

			// 读取数据
			private void read() throws Exception {
				readLock.acquire();
				try {
					Thread.sleep(100);
					System.out.println("读取数据\t" + testData);
				} finally {
					readLock.release();
				}
			}

			// 等待线程结束,防止test方法调用完成后,当前线程直接退出,导致控制台无法输出信息
			public void waitThread() throws InterruptedException {
				for (Thread thread : threadSet) {
					thread.join();
				}
			}

			// 创建线程方法
			private void createThread(int type) {
				Thread thread = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							if (type == 1) {
								write();
							} else {
								read();
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
				threadSet.add(thread);
				thread.start();
			}

			// 测试方法
			public void test() {
				for (int i = 0; i < 5; i++) {
					createThread(1);
				}
				for (int i = 0; i < 8; i++) {
					createThread(2);
				}
			}
		}

		ReadWriteLockTest readWriteLockTest = new ReadWriteLockTest();
		readWriteLockTest.test();
		readWriteLockTest.waitThread();
	}

	// 信号量
	@Test
	public void semaphore() throws Exception {
		// 创建一个信号量
		InterProcessSemaphoreV2 semaphore = new InterProcessSemaphoreV2(client, lockPath, 6);
		// semaphore2 用于模拟其他客户端
		InterProcessSemaphoreV2 semaphore2 = new InterProcessSemaphoreV2(client2, lockPath, 6);

		// 获取一个许可
		System.out.println("获取一个许可");
		Lease lease = semaphore.acquire();
		System.out.println("lease=" + lease);
		// semaphore.getParticipantNodes() 会返回当前参与信号量的节点列表,俩个客户端所获取的信息相同
		System.out.println(" semaphore.getParticipantNodes()=" + semaphore.getParticipantNodes());
		System.out.println("semaphore2.getParticipantNodes()=" + semaphore2.getParticipantNodes());

		// 超时获取一个许可
		System.out.println("超时获取一个许可");
		Lease lease2 = semaphore2.acquire(2, TimeUnit.SECONDS);
		System.out.println("lease2=" + lease2);
		System.out.println(" semaphore.getParticipantNodes()=" + semaphore.getParticipantNodes());
		System.out.println("semaphore2.getParticipantNodes()=" + semaphore2.getParticipantNodes());

		// 获取多个许可,参数为许可数量
		System.out.println("获取多个许可");
		Collection<Lease> leases = semaphore.acquire(2);
		System.out.println("leases.size()=" + leases.size());
		System.out.println(" semaphore.getParticipantNodes()=" + semaphore.getParticipantNodes());
		System.out.println("semaphore2.getParticipantNodes()=" + semaphore2.getParticipantNodes());

		// 超时获取多个许可,第一个参数为许可数量
		System.out.println("超时获取多个许可");
		Collection<Lease> leases2 = semaphore2.acquire(2, 2, TimeUnit.SECONDS);
		System.out.println("leases2.size()=" + leases2.size());
		System.out.println(" semaphore.getParticipantNodes()=" + semaphore.getParticipantNodes());
		System.out.println("semaphore2.getParticipantNodes()=" + semaphore2.getParticipantNodes());

		// 目前 semaphore 已经获取 3 个许可,semaphore2 也获取 3 个许可,加起来为 6 个,所以他们无法在进行许可获取
		// 无法获取许可
		Lease lease3 = semaphore.acquire(2, TimeUnit.SECONDS);
		System.out.println("lease3=" + lease3);
		Lease lease4 = semaphore2.acquire(2, TimeUnit.SECONDS);
		System.out.println("lease4=" + lease4);

		semaphore.returnLease(lease);
		semaphore2.returnLease(lease2);
		semaphore.returnAll(leases);
		semaphore2.returnAll(leases2);
	}

	/*
	 * @Test public void semaphore2() throws Exception { SharedCount sharedCount
	 * = new SharedCount(client, lockPath, 6); sharedCount.start(); SharedCount
	 * sharedCount2 = new SharedCount(client2, lockPath, 6);
	 * sharedCount2.start(); System.out.println(sharedCount.getCount());
	 * System.out.println(sharedCount2.getCount()); // 创建一个信号量
	 * InterProcessSemaphoreV2 semaphore = new InterProcessSemaphoreV2(client,
	 * lockPath, sharedCount); System.out.println(sharedCount.getCount());
	 * InterProcessSemaphoreV2 semaphore2 = new InterProcessSemaphoreV2(client2,
	 * lockPath, sharedCount2); System.out.println(sharedCount2.getCount()); //
	 * semaphore2 用于模拟其他客户端 System.out.println(sharedCount.getCount());
	 * System.out.println(sharedCount2.getCount()); // 获取一个许可 Lease lease =
	 * semaphore.acquire(); System.out.println(sharedCount.getCount());
	 * System.out.println(sharedCount2.getCount()); Assert.assertNotNull(lease);
	 * // semaphore.getParticipantNodes() 会返回当前参与信号量的节点列表,俩个客户端所获取的信息相同
	 * Assert.assertEquals(semaphore.getParticipantNodes(),
	 * semaphore2.getParticipantNodes());
	 * 
	 * // 超时获取一个许可 Lease lease2 = semaphore2.acquire(2, TimeUnit.SECONDS);
	 * Assert.assertNotNull(lease2);
	 * Assert.assertEquals(semaphore.getParticipantNodes(),
	 * semaphore2.getParticipantNodes());
	 * 
	 * // 获取多个许可,参数为许可数量 Collection<Lease> leases = semaphore.acquire(2);
	 * Assert.assertTrue(leases.size() == 2);
	 * Assert.assertEquals(semaphore.getParticipantNodes(),
	 * semaphore2.getParticipantNodes());
	 * 
	 * // 超时获取多个许可 Collection<Lease> leases2 = semaphore2.acquire(2, 2,
	 * TimeUnit.SECONDS); Assert.assertTrue(leases2.size() == 2);
	 * Assert.assertEquals(semaphore.getParticipantNodes(),
	 * semaphore2.getParticipantNodes());
	 * 
	 * // 目前 semaphore 已经获取 3 个许可,semaphore2 也获取 3 个许可,加起来为 6 个,所以他们无法再进行许可获取
	 * Assert.assertNull(semaphore.acquire(2, TimeUnit.SECONDS));
	 * Assert.assertNull(semaphore2.acquire(2, TimeUnit.SECONDS));
	 * 
	 * // 释放一个许可 semaphore.returnLease(lease); semaphore2.returnLease(lease2);
	 * // 释放多个许可 semaphore.returnAll(leases); semaphore2.returnAll(leases2);
	 * 
	 * sharedCount.close(); sharedCount2.close(); }
	 */

}
