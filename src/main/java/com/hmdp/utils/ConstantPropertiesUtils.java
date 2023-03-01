package com.hmdp.utils;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author 小新
 * date 2023/2/24
 * @apiNode
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss.file")
public class ConstantPropertiesUtils{

    //读取配置文件内容
    private String endpoint;
    private String keyid;
    private String keysecret;
    private String bucketname;
}
