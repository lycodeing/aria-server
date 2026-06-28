package com.aidevplatform.knowledge.infrastructure.persistence.mapper;

import com.aidevplatform.knowledge.infrastructure.persistence.do_.ChunkHitDO;
import com.aidevplatform.knowledge.infrastructure.persistence.entity.KnowledgeChunkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Chunk Mapper。
 * 标准 CRUD 由 BaseMapper 提供。
 * 向量检索（pgvector <=> 运算符）和全文检索 SQL 定义在 KnowledgeChunkMapper.xml，
 * 使用 CDATA 包裹避免 XML 特殊字符转义问题。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {

    /**
     * 向量相似度检索（pgvector 余弦距离）。
     * 单表查询：kb_id / doc_status 冗余在 knowledge_chunk，无需 JOIN knowledge_doc。
     * SQL 详见 KnowledgeChunkMapper.xml，<=> 运算符用 CDATA 包裹。
     *
     * @param vec  pgvector 格式字符串，如 [0.1,0.2,...]，由 VectorUtils.toStr() 生成
     * @param kbId 目标知识库 ID
     * @param topK 返回条数上限
     * @return 按相关性降序排列的命中列表
     */
    List<ChunkHitDO> selectByVector(@Param("vec")  String vec,
                                    @Param("kbId")  String kbId,
                                    @Param("topK")  int    topK);

    /**
     * 全文检索（PostgreSQL TF-IDF 近似，依赖 pg_jieba 扩展）。
     * 未安装 pg_jieba 时降级为 simple 字典（按空格/标点切分）。
     * SQL 详见 KnowledgeChunkMapper.xml，@@ 运算符用 CDATA 包裹。
     *
     * @param query 搜索关键词
     * @param kbId  目标知识库 ID
     * @param topK  返回条数上限
     * @return 按 TF-IDF 分降序排列的命中列表
     */
    List<ChunkHitDO> selectByFullText(@Param("query") String query,
                                      @Param("kbId")  String kbId,
                                      @Param("topK")  int    topK);
}
