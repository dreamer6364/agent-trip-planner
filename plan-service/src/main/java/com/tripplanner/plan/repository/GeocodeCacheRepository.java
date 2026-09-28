package com.tripplanner.plan.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripplanner.plan.entity.GeocodeCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 地理编码缓存数据访问层
 */
@Mapper
public interface GeocodeCacheRepository extends BaseMapper<GeocodeCache> {

    /**
     * 根据查询哈希查找缓存
     */
    @Select("SELECT * FROM geocode_cache WHERE query_hash = #{hash} AND source = #{source}")
    GeocodeCache findByHash(@Param("hash") String hash, @Param("source") String source);

    /**
     * 批量查找缓存
     */
    @Select("SELECT * FROM geocode_cache WHERE query_hash IN (${hashes}) AND source = #{source}")
    List<GeocodeCache> findByHashes(@Param("hashes") String hashes, @Param("source") String source);
}