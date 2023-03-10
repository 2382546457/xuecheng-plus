package com.xuecheng.media.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.j256.simplemagic.ContentInfo;
//import com.j256.simplemagic.ContentInfoUtil;
import com.j256.simplemagic.ContentInfoUtil;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFileMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.service.MediaFileService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * @author : 小何
 * @Description :
 * @date : 2023-02-09 18:16
 */
@Service
@Slf4j
public class MediaFileServiceImpl extends ServiceImpl<MediaFileMapper, MediaFiles> implements MediaFileService {

    @Autowired
    private MinioClient minioClient;
    @Value("${minio.bucket.files}")
    private String bucket_file;
    @Value("${minio.bucket.videofiles}")
    private String bucket_video;

    @Autowired
    private MediaFileMapper mediaFileMapper;


    /**
     * 检查文件是否存在
     * @param md5
     * @return
     */
    @Override
    public RestResponse<Boolean> checkFile(String md5) {
        MediaFiles mediaFiles = mediaFileMapper.selectById(md5);
        // 1. 查看是否在数据库中存在
        if (Objects.isNull(mediaFiles)) {
            return RestResponse.success(false);
        }
        // 2. 查看是否在MinIO中存在
        try {
            InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                            .bucket(mediaFiles.getBucket())
                    .object(mediaFiles.getFilePath())
                    .build());
            if (Objects.isNull(inputStream)) {
                return RestResponse.success(false);
            }
        } catch (Exception e) {
            log.error("{}", e);
            return RestResponse.success(false);

        }
        return RestResponse.success(true);
    }


    @Override
    public RestResponse<Boolean> checkChunk(String fileMd5, int chunkIndex) {

        //得到分块文件所在目录
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        //分块文件的路径
        String chunkFilePath = chunkFileFolderPath + chunkIndex;

        //查询文件系统分块文件是否存在
        //查看是否在文件系统存在
        GetObjectArgs getObjectArgs = GetObjectArgs.builder()
                .bucket(bucket_video)
                .object(chunkFilePath)
                .build();
        try {
            InputStream inputStream = minioClient.getObject(getObjectArgs);
            if(Objects.isNull(inputStream)){
                //文件不存在
                return RestResponse.success(false);
            }
        }catch (Exception e){
            //文件不存在
            return RestResponse.success(false);
        }


        return RestResponse.success(true);
    }


    /**
     * 查询本机构所有文件
     * @param companyId 本机构id
     * @param pageParams 分页参数: 当前页码、每页数据数
     * @param dto 查询条件
     * @return 返回结果
     */
    @Override
    public PageResult<MediaFiles> queryMediaFiles(Long companyId, PageParams pageParams, QueryMediaParamsDto dto) {
        LambdaQueryWrapper<MediaFiles> queryWrapper = new LambdaQueryWrapper<>();
//        queryWrapper.eq(MediaFiles::getFilename, dto.getFilename());
        queryWrapper.like(MediaFiles::getFilename, dto.getFilename());
        if (!Strings.isEmpty(dto.getFileType())) {
            queryWrapper.eq(MediaFiles::getFileType, dto.getFileType());
        }
        Page<MediaFiles> page = new Page<>(pageParams.getPageNo(), pageParams.getPageSize());
        mediaFileMapper.selectPage(page, queryWrapper);
        List<MediaFiles> records = page.getRecords();
        long total = page.getTotal();
        PageResult<MediaFiles> pageResult = new PageResult<>(records, total, pageParams.getPageNo(), pageParams.getPageSize());
        return pageResult;
    }



    /**
     * 上传文件
     * @param companyId 上传的公司的id
     * @param bytes 需要上传的文件
     * @param dto 文件的信息
     * @param folder 文件路径
     * @param objectName 文件名
     * @return
     */
    @Override
    public UploadFileResultDto uploadFile(Long companyId,
                                          byte[] bytes,
                                          UploadFileParamsDto dto,
                                          String folder,
                                          String objectName) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        // 1. 上传至minio
        // 如果未指定文件夹，以当前 年/月/日 作为文件夹目录
        if (StringUtils.isEmpty(folder)) {
            folder = getFolder(LocalDate.now());
        }
        // 如果没以 "/" 结尾，补上
        if (!folder.endsWith("/")) {
            folder += "/";
        }
        // 如果没指定文件名称, 生成一个以 "时:分:秒-UUID" 的文件名
        String filename = dto.getFilename();
        if (StringUtils.isEmpty(objectName)) {
            objectName = generateFileName(LocalDateTime.now()) + filename.substring(filename.lastIndexOf("."));
        }
        // 完整的文件名 = 文件目录 + 文件名
        objectName = folder + objectName;
        // 上传
        addMediaToMinIO(bytes, bucket_file, objectName);

        // 2. 插入数据库, 为防止事务失效，使用代理类的addMediaToDB方法。
        String fileMd5 = DigestUtils.md5DigestAsHex(bytes);
        MediaFileServiceImpl mediaFileService = (MediaFileServiceImpl) AopContext.currentProxy();
        MediaFiles mediaFiles = mediaFileService.addMediaToDB(companyId, fileMd5, dto, bucket_file, objectName);

        // 3. 封装数据返回
        UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
        BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);

        return uploadFileResultDto;
    }
    /**
     * 上传分块
     * @param md5 上传的分块所属的文件
     * @param chunk 上传的分块的序号
     * @param bytes 上传的分块的字节内容
     * @return
     */
    @Override
    public RestResponse uploadChunk(String md5, int chunk, byte[] bytes) {
        // 获取文件应该放在哪个目录
        String chunkFileFolderPath = getChunkFileFolderPath(md5);
        // 获取分块的完整路径
        String chunkPath = chunkFileFolderPath + chunk;

        try {
            addMediaToMinIO(bytes, bucket_video, chunkPath);

        } catch (Exception e) {
            throw new RuntimeException("文件上传失败!");
        }

        return RestResponse.success();
    }


    /**
     * 合并分块
     * @param companyId 该文件属于哪个机构
     * @param chunkTotal 一共有多少个分块
     * @param dto 需要合并的文件的信息
     * @return
     */
    @Override
    public RestResponse mergeChunks(Long companyId, String fileMd5, int chunkTotal, UploadFileParamsDto dto) throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        FileInputStream inputStream = null;
        RandomAccessFile raf_write = null;

        try {
            // 1. 下载分块
            File[] files = downloadChunks(fileMd5, chunkTotal);


            // 2. 合并分块
            // 2.1 获取文件扩展名
            String filename = dto.getFilename();
            String extension = filename.substring(filename.lastIndexOf("."));
            // 2.2 创建临时文件用于分块的合并
            File tempMergeFile = null;
            try {
                tempMergeFile = File.createTempFile("merge", extension);
            } catch (IOException e) {
                log.error("创建合并临时文件失败, 文件md5:{}", fileMd5);
                throw new IOException("创建合并临时文件失败");
            }

            // 2.3 开始合并
            raf_write = new RandomAccessFile(tempMergeFile, "rw");
            byte[] b = new byte[1024];
            for (File file : files) {
                RandomAccessFile raf_read = new RandomAccessFile(file, "r");
                int len = -1;
                while ((len = raf_read.read(b)) != -1) {
                    raf_write.write(b, 0, len);
                }
            }

            // 3. 校验合并后的文件与原始文件的md5值是否相同
            inputStream = new FileInputStream(tempMergeFile);
            String mergeFileMd5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(inputStream);
            if (!fileMd5.equals(mergeFileMd5)) {
                log.error("合并的文件与上传的文件不同");
                throw new RuntimeException("合并文件校验失败!");
            }

            // 4. 合并后的文件上传的文件系统
            String filePath = getAbsoluteFilePath(fileMd5, extension);
            addMediaToMinIO(tempMergeFile.getAbsolutePath(), bucket_video, filePath);
            // 5. 合并后的文件入库
            addMediaToDB(companyId, fileMd5, dto, bucket_video, filePath);

            // 6. 删除临时文件
            if (!Objects.isNull(files)) {
                for (File file : files) {
                    if (file.exists()) {
                        file.delete();
                    }
                }
            }
            if (!Objects.isNull(tempMergeFile)) {
                if (tempMergeFile.exists()) {
                    tempMergeFile.delete();
                }
            }
        } finally {
            if (!Objects.isNull(inputStream)) {
                inputStream.close();
            }
            if (!Objects.isNull(raf_write)) {
                raf_write.close();
            }
        }


        return RestResponse.success();
    }

    /**
     * 下载所有分块
     * @param md5 md5值
     * @param chunkTotal 一共有多少分块
     * @return
     */
    private File[] downloadChunks(String md5, int chunkTotal) {
        // 分块文件所在目录
        String chunkFileFolderPath = getChunkFileFolderPath(md5);
        File[] files = new File[chunkTotal];
        File tempFile = null;
        // 开始下载
        for (int i = 0; i < chunkTotal; i++) {
            String chunkPath = chunkFileFolderPath + i;
            try {
                tempFile = File.createTempFile("chunk", null);
            } catch (IOException e) {
                throw new RuntimeException("创建临时文件出错!");
            }
            GetObjectArgs build = GetObjectArgs.builder()
                    .bucket(bucket_video)
                    .object(chunkPath)
                    .build();
            FileOutputStream fileOutputStream = null;

            try {
                InputStream inputStream = minioClient.getObject(build);
                fileOutputStream = new FileOutputStream(tempFile);
                IOUtils.copy(inputStream, fileOutputStream);
                files[i] = tempFile;
            } catch (Exception e) {
                throw new RuntimeException("下载文件分块失败, md5:" + md5 + "; 第" + i + "个分块");
            } finally {
                if (!Objects.isNull(fileOutputStream)) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }
        }
        return files;
    }

    /**
     * 将服务器中合并的文件上传至MinIO
     * @param filePath 文件路径
     * @param bucket 哪个桶
     * @param objectName 对象名称
     */
    private void addMediaToMinIO(String filePath, String bucket, String objectName) throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        UploadObjectArgs uploadObjectArgs = UploadObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .filename(filePath)
                .build();
        minioClient.uploadObject(uploadObjectArgs);
    }

    /**
     * 根据后缀名获取文件类型
     * @param extension 后缀名
     * @return
     */
    private String getContentTypeByExtension(String extension) {
        String contentType = null;
        ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
        if (!Objects.isNull(extensionMatch)) {
            contentType = extensionMatch.getMimeType();
        }
        return contentType;
    }

    /**
     * 将文件上传至MinIO
     * @param bytes 文件的字节数组
     * @param bucket 文件上传到哪个桶
     * @param objectName 文件全限定名称
     */
    public void addMediaToMinIO(byte[] bytes, String bucket, String objectName) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        // 文件类型默认为默认类型:application/octet-stream
        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        if (objectName.contains(".")) {
            // 文件后缀名
            String extension = objectName.substring(objectName.lastIndexOf("."));
            // 根据文件后缀名拿到文件类型
            ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
            // 如果是正确的文件类型，赋值给contentType。如果是离谱的，例如:.abc，pass掉。
            if (!Objects.isNull(extensionMatch)) {
                contentType = extensionMatch.getMimeType();
            }
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .contentType(contentType)
                .stream(inputStream, inputStream.available(), -1)
                .build());
    }

    /**
     * 将文件信息保存到数据库
     * @param companyId 上传人所属的公司/机构的id
     * @param fileId 文件的md5值
     * @param dto 文件信息
     * @param bucket 文件放在哪个桶
     * @param objectName 文件的全限定名
     * @return 返回文件的基本信息
     */
    @Transactional
    public MediaFiles addMediaToDB(Long companyId, String fileId,UploadFileParamsDto dto, String bucket, String objectName) {
        // 先看看数据库中有没有
        String fileMd5 = fileId;
        MediaFiles mediaFiles = mediaFileMapper.selectById(fileMd5);
        // 如果数据库中没有这个文件, 插入
        if (Objects.isNull(mediaFiles)) {
            // 封装数据
            mediaFiles = new MediaFiles();
            BeanUtils.copyProperties(dto, mediaFiles);
            mediaFiles.setId(fileMd5);
            mediaFiles.setFileId(fileMd5);
            mediaFiles.setCompanyId(companyId);
            mediaFiles.setFilename(dto.getFilename());
            mediaFiles.setBucket(bucket);
            mediaFiles.setFilePath(objectName);
            // 获取文件类型，如果是特殊文件无法直接访问，不能生成url
            String filename = dto.getFilename();
            String extension = null;
            if (!StringUtils.isEmpty(filename)) {
                extension = filename.substring(filename.lastIndexOf("."));
            }
            String mimeType = getContentTypeByExtension(extension);
            if (mimeType.contains("image") || mimeType.contains("mp4")) {
                mediaFiles.setUrl("/" + bucket + "/" + objectName);
            }


            mediaFiles.setCreateDate(LocalDateTime.now());
            mediaFiles.setStatus("1");
            mediaFiles.setAuditStatus("002003");
            int insert = mediaFileMapper.insert(mediaFiles);
            if (insert != 1) {
                throw new RuntimeException("文件插入数据库失败!");
            }
        } else {
            throw new RuntimeException("该文件已存在!无法重复上传");
        }
        return mediaFiles;
    }



    // 生成文件目录
    public String getFolder(LocalDate localDate) {
        String[] split = localDate.toString().split("-");
        // 如: 2023/2/9
        return split[0] + "/" + split[1] + "/" + split[2] + "/";
    }

    // 生成给文件名
    public String generateFileName(LocalDateTime localDateTime) {
        int hour = localDateTime.getHour();
        int minute = localDateTime.getMinute();
        int second = localDateTime.getSecond();
        String substring = UUID.randomUUID().toString().substring(0, 7);
        return hour + ":" + minute + ":" + second + ":" + substring;

    }

    /**
     * 得到分块文件的目录,上传分块时按照md5进行分块，检查时也按照这个目录检查
     * @param fileMd5
     * @return
     */
    private String getChunkFileFolderPath(String fileMd5) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + "chunk" + "/";
    }
    private String getAbsoluteFilePath(String fileMd5, String extension) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + fileMd5 + extension;
    }


}
