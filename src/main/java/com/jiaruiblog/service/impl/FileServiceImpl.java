package com.jiaruiblog.service.impl;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.IdUtil;
import com.jiaruiblog.common.MessageConstant;
import com.jiaruiblog.entity.Category;
import com.jiaruiblog.entity.DTO.DocumentDTO;
import com.jiaruiblog.entity.Tag;
import com.jiaruiblog.entity.vo.DocumentVO;
import com.jiaruiblog.service.IFileService;
import com.jiaruiblog.utils.ApiResult;
import com.jiaruiblog.utils.PDFUtil;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.DeleteResult;
import com.jiaruiblog.entity.FileDocument;


import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Field;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileServiceImpl implements IFileService {

    private static String collectionName = "fileDatas";

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private GridFsTemplate gridFsTemplate;

    @Autowired
    private GridFSBucket gridFSBucket;

    @Autowired
    private CategoryServiceImpl categoryServiceImpl;

    @Autowired
    private CommentServiceImpl commentServiceImpl;

    @Autowired
    private CollectServiceImpl collectServiceImpl;

    @Autowired
    private TagServiceImpl tagServiceImpl;

    @Autowired
    private ElasticServiceImpl elasticServiceImpl;


    /**
     * js?????????????????????
     *
     * @param fileDocument
     * @param inputStream
     * @return
     */
    @Override
    public FileDocument saveFile(FileDocument fileDocument, InputStream inputStream) {
        //????????????????????????????????????
        FileDocument dbFile = getByMd5(fileDocument.getMd5());
        if (dbFile != null) {
            return dbFile;
        }

        //GridFSInputFile inputFile = gridFsTemplate

        String gridfsId = uploadFileToGridFS(inputStream, fileDocument.getContentType());
        fileDocument.setGridfsId(gridfsId);

        fileDocument = mongoTemplate.save(fileDocument, collectionName);

        // TODO ???????????????????????????

        return fileDocument;
    }

    /**
     * ??????????????????
     *
     * @param md5
     * @param file
     * @return
     */
    @Override
    public FileDocument saveFile(String md5, MultipartFile file) {
        //????????????????????????????????????
        FileDocument fileDocument = getByMd5(md5);
        if (fileDocument != null) {
            return fileDocument;
        }

        fileDocument = new FileDocument();
        fileDocument.setName(file.getOriginalFilename());
        fileDocument.setSize(file.getSize());
        fileDocument.setContentType(file.getContentType());
        fileDocument.setUploadDate(new Date());
        fileDocument.setMd5(md5);
        String suffix = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
        fileDocument.setSuffix(suffix);

        try {
            String gridfsId = uploadFileToGridFS(file.getInputStream(), file.getContentType());
            fileDocument.setGridfsId(gridfsId);
            fileDocument = mongoTemplate.save(fileDocument, collectionName);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        return fileDocument;
    }

    /**
     * ???????????????Mongodb???GridFs???
     *
     * @param in
     * @param contentType
     * @return
     */
    private String uploadFileToGridFS(InputStream in, String contentType) {
        String gridfsId = IdUtil.simpleUUID();
        //??????????????????GridFS???
        gridFsTemplate.store(in, gridfsId, contentType);
        return gridfsId;
    }

    /**
     * ????????????
     *
     * @param id           ??????id
     * @param isDeleteFile ??????????????????
     */
    @Override
    public void removeFile(String id, boolean isDeleteFile) {
        FileDocument fileDocument = mongoTemplate.findById(id, FileDocument.class, collectionName);
        if (fileDocument != null) {
            Query query = new Query().addCriteria(Criteria.where("_id").is(id));
            DeleteResult result = mongoTemplate.remove(query, collectionName);
            if (isDeleteFile) {
                Query deleteQuery = new Query().addCriteria(Criteria.where("filename").is(fileDocument.getGridfsId()));
                gridFsTemplate.delete(deleteQuery);
            }
        }
    }

    /**
     * ????????????
     *
     * @param id ??????id
     * @return
     * @throws IOException
     */
    @Override
    public Optional<FileDocument> getById(String id) {
        FileDocument fileDocument = mongoTemplate.findById(id, FileDocument.class, collectionName);
        if (fileDocument != null) {
            Query gridQuery = new Query().addCriteria(Criteria.where("filename").is(fileDocument.getGridfsId()));
            try {
                GridFSFile fsFile = gridFsTemplate.findOne(gridQuery);
                GridFSDownloadStream in = gridFSBucket.openDownloadStream(fsFile.getObjectId());
                if (in.getGridFSFile().getLength() > 0) {
                    GridFsResource resource = new GridFsResource(fsFile, in);
                    fileDocument.setContent(IoUtil.readBytes(resource.getInputStream()));
                    return Optional.of(fileDocument);
                } else {
                    fileDocument = null;
                    return Optional.empty();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return Optional.empty();
    }

    /**
     * ??????md5??????????????????
     *
     * @param md5
     * @return
     */
    @Override
    public FileDocument getByMd5(String md5) {
        Query query = new Query().addCriteria(Criteria.where("md5").is(md5));
        FileDocument fileDocument = mongoTemplate.findOne(query, FileDocument.class, collectionName);
        return fileDocument;
    }

    @Override
    public List<FileDocument> listFilesByPage(int pageIndex, int pageSize) {
        log.info("xxx" + pageIndex);
        log.info("yyy" +pageSize);
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "uploadDate"));
        long skip = (pageIndex - 1) * pageSize;
        query.skip(skip);
        query.limit(pageSize);
        Field field = query.fields();
        field.exclude("content");
        List<FileDocument> files = mongoTemplate.find(query, FileDocument.class, collectionName);
        return files;
    }

    /**
     * @Author luojiarui
     * @Description // ?????????????????????????????????
     * @Date 11:12 ?????? 2022/6/22
     * @Param [pageIndex, pageSize, ids]
     * @return java.util.List<com.jiaruiblog.entity.FileDocument>
     **/
    public List<FileDocument> listAndFilterByPage(int pageIndex, int pageSize, Collection<String> ids) {
        if(ids == null || ids.isEmpty()) {
            return null;
        }
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "uploadDate"));
        long skip = (pageIndex - 1) * pageSize;
        query.skip(skip);
        query.limit(pageSize);
        // ??????????????????
        query.addCriteria(Criteria.where("_id").in(ids));

        Field field = query.fields();
        field.exclude("content");
        List<FileDocument> files = mongoTemplate.find(query, FileDocument.class, collectionName);
        return files;
    }

    @Override
    public ApiResult list(DocumentDTO documentDTO) {
        log.info(MessageFormat.format(">>>>>>>????????????>>>>>>????????????{0}", documentDTO.toString()));

        List<DocumentVO> documentVOS;
        DocumentVO documentVO = new DocumentVO();
        List<FileDocument> fileDocuments = Lists.newArrayList();

        switch (documentDTO.getType()) {
            case ALL:
                fileDocuments = listFilesByPage(documentDTO.getPage(),documentDTO.getRows());
                break;
            case TAG:
                Tag tag = tagServiceImpl.queryByTagId(documentDTO.getTagId());
                if(tag == null) {
                    break;
                }
                List<String> fileIdList1 = tagServiceImpl.queryDocIdListByTagId(tag.getId());
                fileDocuments = listAndFilterByPage(documentDTO.getPage(), documentDTO.getRows(), fileIdList1);
                break;
            case FILTER:
                Set<String> docIdSet = new HashSet<>();
                String keyWord = Optional.ofNullable(documentDTO).map(DocumentDTO::getFilterWord).orElse("");
                // ???????????? ??????
                docIdSet.addAll(categoryServiceImpl.fuzzySearchDoc(keyWord));
                // ???????????? ??????
                docIdSet.addAll(tagServiceImpl.fuzzySearchDoc(keyWord));
                // ???????????? ????????????
                docIdSet.addAll(fuzzySearchDoc(keyWord));
                // ???????????? ????????????
                docIdSet.addAll(commentServiceImpl.fuzzySearchDoc(keyWord));
                List<FileDocument> esDoc = null;
                try {
                     esDoc = elasticServiceImpl.search(keyWord);
                    Set<String> existIds = esDoc.stream().map(FileDocument::getId).collect(Collectors.toSet());
                    docIdSet.removeAll(existIds);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fileDocuments = listAndFilterByPage(documentDTO.getPage(), documentDTO.getRows(), docIdSet);
                if(esDoc != null){
                    fileDocuments = Optional.ofNullable(fileDocuments).orElse(new ArrayList<>());
                    System.out.println(fileDocuments);
                    System.out.println(esDoc);
                    fileDocuments.addAll(esDoc);
                }
                break;
            case CATEGORY:
                Category category = categoryServiceImpl.queryById(documentDTO.getCategoryId());
                if(category == null ) {
                    break;
                }
                List<String> fileIdList = categoryServiceImpl.queryDocListByCategory(category);
                fileDocuments = listAndFilterByPage(documentDTO.getPage(), documentDTO.getRows(), fileIdList);
                break;
            default:
                return ApiResult.error(MessageConstant.PARAMS_ERROR_CODE, MessageConstant.PARAMS_IS_NOT_NULL);
        }
        documentVOS = convertDocuments(fileDocuments);
        if(documentVOS != null && !documentVOS.isEmpty()) {
            log.info(MessageFormat.format(">>>>>>>??????????????????>>>>>>?????????{0}", documentVOS.size()));
        }
        return ApiResult.success(documentVOS);
    }

    /**
     * @Author luojiarui
     * @Description // ???????????????????????????
     * @Date 9:27 ?????? 2022/6/23
     * @Param [id]
     * @return com.jiaruiblog.utils.ApiResult
     **/
    @Override
    public ApiResult detail(String id) {
        FileDocument fileDocument = queryById(id);
        if( fileDocument == null ) {
            return ApiResult.error(MessageConstant.PROCESS_ERROR_CODE, MessageConstant.PARAMS_LENGTH_REQUIRED);
        }
        // ????????????????????????????????????????????????????????????????????????????????????????????????????????????
        return ApiResult.success(convertDocument(null, fileDocument));
    }

    @Override
    public ApiResult remove(String id) {
        if( !isExist(id) ) {
            return ApiResult.error(MessageConstant.PROCESS_ERROR_CODE, MessageConstant.OPERATE_FAILED);
        }
        // ????????????????????????????????????????????????????????????
        log.info(MessageFormat.format(">>>>>????????????{0}>>>>>>>", id));
        removeFile(id, true);
        commentServiceImpl.removeByDocId(id);
        categoryServiceImpl.removeRelateByDocId(id);
        collectServiceImpl.removeRelateByDocId(id);
        tagServiceImpl.removeRelateByDocId(id);

        return ApiResult.success(MessageConstant.SUCCESS);
    }

    /**
     * @Author luojiarui
     * @Description //TODO
     * @Date 10:16 ?????? 2022/6/21
     * @Param fileDocuments
     * @return java.util.List<com.jiaruiblog.entity.vo.DocumentVO>
     **/
    private List<DocumentVO> convertDocuments(List<FileDocument> fileDocuments) {
        if( fileDocuments == null) {
            return null;
        }
        List<DocumentVO> documentVOS = Lists.newArrayList();
        for(FileDocument fileDocument : fileDocuments) {
            DocumentVO documentVO = new DocumentVO();
            documentVO = convertDocument(documentVO, fileDocument);
            documentVOS.add(documentVO);
        }
        return documentVOS;
    }

    /**
     * @Author luojiarui
     * @Description //TODO
     * @Date 10:24 ?????? 2022/6/21
     * @Param [documentVO, fileDocument]
     * @return com.jiaruiblog.entity.vo.DocumentVO
     **/
    public DocumentVO convertDocument(DocumentVO documentVO, FileDocument fileDocument) {
        documentVO = Optional.ofNullable(documentVO).orElse(new DocumentVO());
        if(fileDocument == null ){
            return documentVO;
        }
        documentVO.setId(fileDocument.getId());
        documentVO.setSize(fileDocument.getSize());
        documentVO.setTitle(fileDocument.getName());
        documentVO.setDescription(fileDocument.getDescription());
        documentVO.setUserName("luojiarui");
        documentVO.setCreateTime(fileDocument.getUploadDate());
        documentVO.setThumbId(fileDocument.getThumbId());
        // ???????????????id???????????? ????????? ?????????????????? ??????
        String docId = fileDocument.getId();
        log.info(MessageFormat.format(">>>>>>>> ???????????????id??? {0}>>>>>>>", docId));
        documentVO.setCommentNum(commentServiceImpl.commentNum(docId));
        documentVO.setCollectNum(collectServiceImpl.collectNum(docId));
        documentVO.setCategoryVO(categoryServiceImpl.queryByDocId(docId));
        documentVO.setTagVOList(tagServiceImpl.queryByDocId(docId));
        return documentVO;
    }

    /**
     * ????????????
     * @param keyWord
     * @return
     */
    public List<String> fuzzySearchDoc(String keyWord) {
        if(keyWord == null || "".equalsIgnoreCase(keyWord)) {
            return null;
        }
        Pattern pattern = Pattern.compile("^.*"+keyWord+".*$", Pattern.CASE_INSENSITIVE);
        Query query = new Query();
        query.addCriteria(Criteria.where("name").regex(pattern));

        List<FileDocument> documents = mongoTemplate.find(query, FileDocument.class, collectionName);
        return documents.stream().map(FileDocument::getId).collect(Collectors.toList());
    }

    //Pattern pattern=Pattern.compile("^.*"+pattern_name+".*$", Pattern.CASE_INSENSITIVE);
    //query.addCriteria(Criteria.where("name").regex(pattern))???


    /**
     * ?????????????????????id??????????????????
     * @param docId
     * @return
     */
    public boolean isExist(String docId) {
        if(docId == null || "".equals(docId)) {
            return false;
        }
        FileDocument fileDocument = queryById(docId);
        if(fileDocument == null) {
            return false;
        }
        return true;
    }

    /**
     * ?????????????????????user
     * @param docId
     * @return
     */
    public FileDocument queryById(String docId) {
        return mongoTemplate.findById(docId, FileDocument.class, collectionName);
    }

    /**
     * @Author luojiarui
     * @Description // ????????????
     * @Date 4:40 ?????? 2022/6/26
     * @Param []
     * @return java.lang.Integer
     **/
    public long countAllFile() {
        return mongoTemplate.getCollection(collectionName).estimatedDocumentCount();
    }

    /**
     * @Author luojiarui
     * @Description //??????pdf??????????????????????????????
     * @Date 7:49 ?????? 2022/7/24
     * @Param [inputStream, fileDocument]
     * @return void
     **/
    @Async
    @Override
    public void updateFileThumb(InputStream inputStream, FileDocument fileDocument) throws FileNotFoundException {
        String path = "thumbnail";   // ??????pdf???????????????
        String picPath = path + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()) + ".png";
        String gridfsId = IdUtil.simpleUUID();
        log.info("====??????????????????====");
        if(fileDocument.getSuffix().equals(".pdf")) {
            // ???pdf?????????????????????????????????????????????
            PDFUtil.pdfThumbnail(inputStream, picPath);

            if(new File(picPath).exists()) {
                String contentType = "image/png";
                FileInputStream in = new FileInputStream(picPath);
                //??????????????????GridFS
                gridFsTemplate.store(in, gridfsId, contentType);
                new File(picPath).delete();
            }
        }

        Query query = new Query().addCriteria(Criteria.where("_id").is(fileDocument.getId()));;
        Update update = new Update().set("thumbId", gridfsId);
        mongoTemplate.updateFirst(query, update, FileDocument.class, collectionName);
        log.info(String.valueOf(fileDocument));
        log.info("====????????????id====" + gridfsId);
    }

    /**
     * @Author luojiarui
     * @Description //???????????????id??????????????????
     * @Date 7:59 ?????? 2022/7/24
     * @Param [thumbId]
     * @return java.io.InputStream
     **/
    @Override
    public InputStream getFileThumb(String thumbId) {
        log.info("thumid====????????????" + thumbId);
        if ( StringUtils.hasText(thumbId)) {
            Query gridQuery = new Query().addCriteria(Criteria.where("filename").is(thumbId));
            try {
                GridFSFile fsFile = gridFsTemplate.findOne(gridQuery);
                log.info("=====??????????????????=====" + fsFile.toString());
                GridFSDownloadStream in = gridFSBucket.openDownloadStream(fsFile.getObjectId());
                if (in.getGridFSFile().getLength() > 0) {
                    GridFsResource resource = new GridFsResource(fsFile, in);
                    return resource.getInputStream();
                } else {
                    return null;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        return null;
    }

    public static void main(String[] args) {
        // DocumentDTO documentDTO = new DocumentDTO();

        DocumentDTO documentDTO = null;
        String keyWord = documentDTO.getFilterWord();
        // String keyWord = Optional.ofNullable(documentDTO).map(item -> item.getFilterWord()).orElse(null);
        System.out.println(keyWord);
    }
}
