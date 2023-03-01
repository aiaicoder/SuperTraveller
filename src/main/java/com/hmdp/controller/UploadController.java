package com.hmdp.controller;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.hmdp.dto.Result;
import com.hmdp.utils.ConstantPropertiesUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.InputStream;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    @Resource
    private ConstantPropertiesUtils constantPropertiesUtils;
    /**
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }
     **/

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile file) {
            // Endpoint以华东1（杭州）为例，其它Region请按实际情况填写。
            String endpoint = constantPropertiesUtils.getEndpoint();
            // 阿里云账号AccessKey拥有所有API的访问权限，风险很高。强烈建议您创建并使用RAM用户进行API访问或日常运维，请登录RAM控制台创建RAM用户。
            String accessKeyId = constantPropertiesUtils.getKeyid();
            String accessKeySecret = constantPropertiesUtils.getKeysecret();
            // 填写Bucket名称，例如examplebucket。
            String bucketName = constantPropertiesUtils.getBucketname();
            try{
                // 创建OSSClient实例。
                OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
                //获取上传文件输入流
                InputStream inputStream = file.getInputStream();
                //获取文件的名称
                String filename = file.getOriginalFilename();
                //防止名字重复
                String s = cn.hutool.core.lang.UUID.randomUUID().toString(true);
                filename = s+filename;
                //2.将文件进行分类
                //2022/12/9/xx.jpg
                //获取当前日期
                String dataPath = new DateTime().toString("yyyy/mm/dd");
                //拼接
                filename = dataPath + "/" + filename;
                //调用oss方法实现上传
                //第一个参数传入bucketName
                //第二个参数上传到oss文件的路径和文件的名称
                //第三个参数，上传文件流
                ossClient.putObject(bucketName,filename,inputStream);
                //关闭OSSClient
                ossClient.shutdown();
                //把上传之后的文件路径返回
                //需要把上传到阿里云oss路径手动拼接出来
                //https://u-center-avatar.oss-cn-beijing.aliyuncs.com/%E5%BE%AE%E4%BF%A1%E5%9B%BE%E7%89%87_20221127124047.jpg
                String url ="https://myblog-imge.oss-cn-beijing.aliyuncs.com/"+filename;
                return Result.ok(url);
            }catch (Exception e){
                throw new RuntimeException("运行出错");
            }
        }
    /**
    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }
     **/

    @PostMapping("/blog/delete")
    public Result deleteBlogImg(@RequestBody String filename) {
        String bucketName = constantPropertiesUtils.getBucketname();
        // Endpoint以华东1（杭州）为例，其它Region请按实际情况填写。
        String endpoint = constantPropertiesUtils.getEndpoint();
        // 阿里云账号AccessKey拥有所有API的访问权限，风险很高。强烈建议您创建并使用RAM用户进行API访问或日常运维，请登录RAM控制台创建RAM用户。
        String accessKeyId = constantPropertiesUtils.getKeyid();
        String accessKeySecret = constantPropertiesUtils.getKeysecret();
        try {
            OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            ossClient.deleteObject(bucketName,filename);
        }catch (RuntimeException e){
            return Result.fail("错误的文件名称");
        }
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
