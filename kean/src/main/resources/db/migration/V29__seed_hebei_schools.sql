-- 依据教育部《全国高等学校名单》（数据截至 2026-06-17，2026-06-18 发布）补齐河北省普通高等学校。
-- 只补缺失的：school.name 上有唯一索引，INSERT IGNORE 保证本迁移可重复执行。
INSERT IGNORE INTO school (name, province_id, status)
SELECT v.name, (SELECT id FROM province WHERE name = '河北'), 1
FROM (
    SELECT '河北大学' AS name UNION ALL
    SELECT '河北工程大学' UNION ALL
    SELECT '河北地质大学' UNION ALL
    SELECT '河北工业大学' UNION ALL
    SELECT '华北理工大学' UNION ALL
    SELECT '河北科技大学' UNION ALL
    SELECT '河北建筑工程学院' UNION ALL
    SELECT '河北水利电力学院' UNION ALL
    SELECT '河北农业大学' UNION ALL
    SELECT '河北医科大学' UNION ALL
    SELECT '河北北方学院' UNION ALL
    SELECT '承德医学院' UNION ALL
    SELECT '河北师范大学' UNION ALL
    SELECT '保定学院' UNION ALL
    SELECT '河北民族师范学院' UNION ALL
    SELECT '唐山师范学院' UNION ALL
    SELECT '廊坊师范学院' UNION ALL
    SELECT '衡水学院' UNION ALL
    SELECT '石家庄学院' UNION ALL
    SELECT '邯郸学院' UNION ALL
    SELECT '邢台学院' UNION ALL
    SELECT '沧州师范学院' UNION ALL
    SELECT '石家庄铁道大学' UNION ALL
    SELECT '燕山大学' UNION ALL
    SELECT '河北科技师范学院' UNION ALL
    SELECT '唐山学院' UNION ALL
    SELECT '应急管理大学' UNION ALL
    SELECT '中国人民警察大学' UNION ALL
    SELECT '河北体育学院' UNION ALL
    SELECT '河北金融学院' UNION ALL
    SELECT '北华航天工业学院' UNION ALL
    SELECT '河北经贸大学' UNION ALL
    SELECT '中央司法警官学院' UNION ALL
    SELECT '河北传媒学院' UNION ALL
    SELECT '河北工程技术学院' UNION ALL
    SELECT '河北美术学院' UNION ALL
    SELECT '河北科技学院' UNION ALL
    SELECT '河北外国语学院' UNION ALL
    SELECT '河北大学工商学院' UNION ALL
    SELECT '华北理工大学轻工学院' UNION ALL
    SELECT '河北工业职业技术大学' UNION ALL
    SELECT '河北师范大学汇华学院' UNION ALL
    SELECT '河北经贸大学经济管理学院' UNION ALL
    SELECT '河北医科大学临床学院' UNION ALL
    SELECT '河北科技工程职业技术大学' UNION ALL
    SELECT '河北工程大学科信学院' UNION ALL
    SELECT '河北石油职业技术大学' UNION ALL
    SELECT '燕山大学里仁学院' UNION ALL
    SELECT '石家庄铁道大学四方学院' UNION ALL
    SELECT '河北地质大学华信学院' UNION ALL
    SELECT '河北农业大学现代科技学院' UNION ALL
    SELECT '华北理工大学冀唐学院' UNION ALL
    SELECT '保定理工学院' UNION ALL
    SELECT '燕京理工学院' UNION ALL
    SELECT '北京中医药大学东方学院' UNION ALL
    SELECT '沧州交通学院' UNION ALL
    SELECT '河北东方学院' UNION ALL
    SELECT '河北中医药大学' UNION ALL
    SELECT '张家口学院' UNION ALL
    SELECT '河北环境工程学院' UNION ALL
    SELECT '唐山工业职业技术大学' UNION ALL
    SELECT '邢台医学院' UNION ALL
    SELECT '邯郸职业技术学院' UNION ALL
    SELECT '石家庄职业技术学院' UNION ALL
    SELECT '张家口职业技术学院' UNION ALL
    SELECT '河北软件职业技术学院' UNION ALL
    SELECT '河北石油职业技术学院' UNION ALL
    SELECT '河北建材职业技术学院' UNION ALL
    SELECT '河北政法职业学院' UNION ALL
    SELECT '沧州职业技术学院' UNION ALL
    SELECT '河北能源职业技术学院' UNION ALL
    SELECT '石家庄铁路职业技术学院' UNION ALL
    SELECT '保定职业技术学院' UNION ALL
    SELECT '秦皇岛职业技术学院' UNION ALL
    SELECT '石家庄工程职业学院' UNION ALL
    SELECT '石家庄城市经济职业学院' UNION ALL
    SELECT '唐山职业技术学院' UNION ALL
    SELECT '衡水职业技术学院' UNION ALL
    SELECT '河北艺术职业学院' UNION ALL
    SELECT '河北旅游职业学院' UNION ALL
    SELECT '石家庄财经职业学院' UNION ALL
    SELECT '河北交通职业技术学院' UNION ALL
    SELECT '河北化工医药职业技术学院' UNION ALL
    SELECT '石家庄信息工程职业学院' UNION ALL
    SELECT '河北对外经贸职业学院' UNION ALL
    SELECT '保定电力职业技术学院' UNION ALL
    SELECT '河北机电职业技术学院' UNION ALL
    SELECT '渤海石油职业学院' UNION ALL
    SELECT '廊坊职业技术学院' UNION ALL
    SELECT '唐山科技职业技术学院' UNION ALL
    SELECT '石家庄邮电职业技术学院' UNION ALL
    SELECT '河北公安警察职业学院' UNION ALL
    SELECT '石家庄工商职业学院' UNION ALL
    SELECT '石家庄理工职业学院' UNION ALL
    SELECT '石家庄科技信息职业学院' UNION ALL
    SELECT '河北司法警官职业学院' UNION ALL
    SELECT '沧州医学高等专科学校' UNION ALL
    SELECT '河北女子职业技术学院' UNION ALL
    SELECT '石家庄医学高等专科学校' UNION ALL
    SELECT '石家庄经济职业学院' UNION ALL
    SELECT '冀中职业学院' UNION ALL
    SELECT '石家庄人民医学高等专科学校' UNION ALL
    SELECT '河北正定师范高等专科学校' UNION ALL
    SELECT '河北劳动关系职业学院' UNION ALL
    SELECT '石家庄科技职业学院' UNION ALL
    SELECT '沧州幼儿师范高等专科学校' UNION ALL
    SELECT '宣化科技职业学院' UNION ALL
    SELECT '廊坊燕京职业技术学院' UNION ALL
    SELECT '承德护理职业学院' UNION ALL
    SELECT '石家庄幼儿师范高等专科学校' UNION ALL
    SELECT '廊坊卫生职业学院' UNION ALL
    SELECT '河北轨道运输职业技术学院' UNION ALL
    SELECT '保定幼儿师范高等专科学校' UNION ALL
    SELECT '河北工艺美术职业学院' UNION ALL
    SELECT '渤海理工职业学院' UNION ALL
    SELECT '唐山幼儿师范高等专科学校' UNION ALL
    SELECT '曹妃甸职业技术学院' UNION ALL
    SELECT '承德应用技术职业学院' UNION ALL
    SELECT '邯郸幼儿师范高等专科学校' UNION ALL
    SELECT '邯郸科技职业学院' UNION ALL
    SELECT '唐山海运职业学院' UNION ALL
    SELECT '邢台应用技术职业学院' UNION ALL
    SELECT '河北资源环境职业技术学院' UNION ALL
    SELECT '衡水健康科技职业学院' UNION ALL
    SELECT '沧州航空职业学院' UNION ALL
    SELECT '邯郸应用技术职业学院' UNION ALL
    SELECT '秦皇岛工业职业技术学院' UNION ALL
    SELECT '邢台新能源职业学院' UNION ALL
    SELECT '石家庄金融职业学院' UNION ALL
    SELECT '石家庄农林职业学院' UNION ALL
    SELECT '张家口应用技术职业学院' UNION ALL
    SELECT '石家庄康养职业学院' UNION ALL
    SELECT '辛集应用技术职业学院'
) v;

-- 给本省还没有任何校区的学校补一个「主校区」。
-- 现有 231 个校区里有 121 个叫「主校区」，沿用同一约定；这样学校在「省-校-校区」三级
-- 联动里才可选（没有校区的学校用户根本选不中）。
INSERT INTO campus (school_id, name, status)
SELECT s.id, '主校区', 1
FROM school s
JOIN province p ON p.id = s.province_id
WHERE p.name = '河北'
  AND s.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM campus c WHERE c.school_id = s.id AND c.deleted = 0);
