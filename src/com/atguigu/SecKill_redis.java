package com.atguigu;

import java.io.IOException;
import java.util.List;

import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;


/*
 * 1. 秒杀业务需要数据的分析
 * 			限制： 每人最多成功一次！
 * 			
 * 			秒杀产品的库存
 * 				key:  prodid
 * 				value :  string
 * 
 * 			秒杀成功的顾客名单
 * 				key:   prodid
 * 				value:   set
 * 
 * 2.  逻辑的实现
 * 			
 * 			①检查当前用户是否已经秒杀成功
 * 				 sismember(key,userid)
 * 					存在，秒杀失败！
 * 					不存在，进入秒杀流程！
 * 
 * 			②检查产品库存的合法性
 * 				 get(key)
 * 					null :  秒杀未开始或商家还未初始化库存！
 * 					>0   :  可以秒杀
 * 								库存减1， decy key
 * 								将用户加入到成功名单： sadd key userid
 * 					=0   :   秒杀失败！
 * 
 * 3. 使用ab压测工具模拟高并发环境
 * 			 
 * 			ab  [参数]  请求
 * 				常用参数： 
 * 					-n : 总请求数
 * 					-c : 并发数
 * 					-p : 如果发送的是post请求，需要将请求参数编辑正在一个文件中
 * 					-T ： post请求，需要指定-T 为application/x-www-form-urlencoded
 * 
 * 			ab -n 2000 -c 200 -p /root/postarg -T 'application/x-www-form-urlencoded'  http://192.168.0.173:8080/MySeckill/doseckill
 * 
 * 4. 如何解决高并发数据的不一致性（超卖）！
 * 			①在java层面解决，使用synchronized加锁！
 * 					synchronized是悲观锁！导致秒杀不公平！
 * 
 * 			②使用的Redis的乐观锁解决
 * 					存在不公平！造成资源的浪费（抢不完！）
 * 
 * 			③ 理想情况，为了保证秒杀的公平，保证先到的请求一定可以先执行成功！
 * 				
 * 					保证高并发请求的有序性！
 * 
 * 					使用Lua脚本实现！
 * 
 * 			
 * 
 * ab -c 10 -n 100 -p /root/postarg  -T application/x-www-form-urlencoded 
 * http://192.168.6.1:8080/seckill/doseckill
 * 
 */

public class SecKill_redis {

	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SecKill_redis.class);

	// 秒杀的实际方法
	public static boolean doSecKill(String uid, String prodid) throws IOException {
		
//		synchronized (SecKill_redis.class) {
			String productKey="sk:"+prodid+":product";
			String userKey="sk:"+prodid+":user";
			
//			System.out.println("productKey = " + productKey);
//			System.out.println("userKey = " + userKey);
			
			JedisPool jedisPool = JedisPoolUtil.getJedisPoolInstance();
			
			Jedis jedis = jedisPool.getResource();
			
			if(jedis.sismember(userKey, uid)) {
				System.err.println(uid+"已经秒杀过");
				jedis.close();
				return false;
			}
			
			jedis.watch(productKey);
			
			String product = jedis.get(productKey);
			
			if(product==null) {
				System.err.println(prodid+"尚未被商家上架");
				jedis.close();
				return false;
			}else {
				
				int store = Integer.parseInt(product);
				
				if(store==0) {
					System.err.println(prodid+"已经没有库存了");
					jedis.close();
					return false;
				}
				
			}
			
			Transaction trans = jedis.multi();
			
			trans.decr(productKey);
			
			trans.sadd(userKey, uid);
			
			List<Object> result = trans.exec();
			
			if(result==null || result.size()<2) {
				jedis.close();
				
				System.err.println(uid+"====>秒杀失败");
				
				return false;
			}
			
			jedis.close();
			
			System.out.println(uid+"====>秒杀成功");
			
			return true;
//		}
		
	}

}
