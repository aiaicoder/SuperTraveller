package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import org.springframework.stereotype.Service;

/**
 * @author 小新
 * date 2023/2/27
 * @apiNode
 */
@Service
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Integer id);
}
