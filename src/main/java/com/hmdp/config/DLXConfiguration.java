package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.hmdp.utils.MqConstants.*;

@Configuration
public class DLXConfiguration {



    @Bean
    public DirectExchange directExchange(){
        return new DirectExchange(ORDER_EXCHANGE);
    }

    @Bean
    public Queue orderQueue(){
        Queue queue = new Queue(ORDER_QUEUE_NAME);
        queue.addArgument("x-dead-letter-exchange",DLX_EXCHANGE);
        queue.addArgument("x-dead-letter-routing-key","order");
        return queue;
    }

    @Bean
    public Queue creatOrderQueue(){
        return new Queue(CREAT_ORDER_QUEUE);
    }


    @Bean
    public DirectExchange dlxExchange(){
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue dlxQueue(){
        return new Queue(DLX_QUEUE);
    }

    @Bean
    public Binding oderQueueBinding(Queue orderQueue, DirectExchange directExchange){
        return BindingBuilder.bind(orderQueue).to(directExchange).with("order");
    }

    //绑定死信队列和死信交换机
    @Bean
    public Binding dlxQueueBinding(Queue dlxQueue,DirectExchange dlxExchange){
        return BindingBuilder.bind(dlxQueue).to(dlxExchange).with("order");
    }


    @Bean
    public Binding creteOrderQueueBinding(Queue creatOrderQueue, DirectExchange directExchange){
        return BindingBuilder.bind(creatOrderQueue).to(directExchange).with("order");
    }
}