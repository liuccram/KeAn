-- 学校增加省份；回填现有学校为山东；补齐教育部 2025 年全国高校名单中的山东高职（专科）及常见校区
ALTER TABLE school
    ADD COLUMN province VARCHAR(32) NOT NULL DEFAULT '山东' COMMENT '省份' AFTER name;

UPDATE school
SET province = '山东'
WHERE province IS NULL
   OR province = '';

INSERT IGNORE INTO school (name, province, status) VALUES
('山东医学高等专科学校', '山东', 1),
('菏泽医学专科学校', '山东', 1),
('山东商业职业技术学院', '山东', 1),
('山东电力高等专科学校', '山东', 1),
('日照职业技术学院', '山东', 1),
('曲阜远东职业技术学院', '山东', 1),
('青岛职业技术学院', '山东', 1),
('威海职业学院', '山东', 1),
('山东职业学院', '山东', 1),
('山东劳动职业技术学院', '山东', 1),
('莱芜职业技术学院', '山东', 1),
('济宁职业技术学院', '山东', 1),
('潍坊职业学院', '山东', 1),
('烟台职业学院', '山东', 1),
('东营职业学院', '山东', 1),
('聊城职业技术学院', '山东', 1),
('滨州职业学院', '山东', 1),
('山东科技职业学院', '山东', 1),
('山东服装职业学院', '山东', 1),
('德州科技职业学院', '山东', 1),
('山东力明科技职业学院', '山东', 1),
('山东圣翰财贸职业学院', '山东', 1),
('山东水利职业学院', '山东', 1),
('山东畜牧兽医职业学院', '山东', 1),
('青岛飞洋职业技术学院', '山东', 1),
('东营科技职业学院', '山东', 1),
('山东交通职业学院', '山东', 1),
('山东外贸职业学院', '山东', 1),
('青岛酒店管理职业技术学院', '山东', 1),
('山东信息职业技术学院', '山东', 1),
('青岛港湾职业技术学院', '山东', 1),
('山东胜利职业学院', '山东', 1),
('山东经贸职业学院', '山东', 1),
('山东工业职业学院', '山东', 1),
('山东化工职业学院', '山东', 1),
('青岛求实职业技术学院', '山东', 1),
('济南职业学院', '山东', 1),
('烟台工程职业技术学院', '山东', 1),
('潍坊工商职业学院', '山东', 1),
('德州职业技术学院', '山东', 1),
('枣庄科技职业学院', '山东', 1),
('淄博师范高等专科学校', '山东', 1),
('山东中医药高等专科学校', '山东', 1),
('济南工程职业技术学院', '山东', 1),
('山东电子职业技术学院', '山东', 1),
('山东旅游职业学院', '山东', 1),
('山东铝业职业学院', '山东', 1),
('山东杏林科技职业学院', '山东', 1),
('泰山职业技术学院', '山东', 1),
('山东药品食品职业学院', '山东', 1),
('山东商务职业学院', '山东', 1),
('山东轻工职业学院', '山东', 1),
('山东城市建设职业学院', '山东', 1),
('烟台汽车工程职业学院', '山东', 1),
('山东司法警官职业学院', '山东', 1),
('菏泽家政职业学院', '山东', 1),
('山东传媒职业学院', '山东', 1),
('临沂职业学院', '山东', 1),
('枣庄职业学院', '山东', 1),
('山东理工职业学院', '山东', 1),
('山东文化产业职业学院', '山东', 1),
('青岛远洋船员职业学院', '山东', 1),
('济南幼儿师范高等专科学校', '山东', 1),
('济南护理职业学院', '山东', 1),
('泰山护理职业学院', '山东', 1),
('山东海事职业学院', '山东', 1),
('潍坊护理职业学院', '山东', 1),
('潍坊工程职业学院', '山东', 1),
('菏泽职业学院', '山东', 1),
('山东艺术设计职业学院', '山东', 1),
('威海海洋职业学院', '山东', 1),
('山东特殊教育职业学院', '山东', 1),
('烟台黄金职业学院', '山东', 1),
('日照航海工程职业学院', '山东', 1),
('青岛工程职业学院', '山东', 1),
('青岛幼儿师范高等专科学校', '山东', 1),
('烟台幼儿师范高等专科学校', '山东', 1),
('烟台文化旅游职业学院', '山东', 1),
('临沂科技职业学院', '山东', 1),
('青岛航空科技职业学院', '山东', 1),
('潍坊环境工程职业学院', '山东', 1),
('滨州科技职业学院', '山东', 1),
('山东城市服务职业学院', '山东', 1),
('潍坊食品科技职业学院', '山东', 1),
('烟台城市科技职业学院', '山东', 1),
('德州工程职业学院', '山东', 1),
('日照康养职业学院', '山东', 1),
('烟台卫生健康职业学院', '山东', 1),
('山东文化艺术职业学院', '山东', 1),
('聊城科技职业学院', '山东', 1),
('济宁汽车工程职业学院', '山东', 1),
('日照科技职业学院', '山东', 1),
('枣庄应用技术职业学院', '山东', 1),
('临沂城市职业学院', '山东', 1),
('菏泽生物医药职业学院', '山东', 1);

INSERT IGNORE INTO campus (school_id, name, status)
SELECT s.id, c.name, 1
FROM school s
JOIN (
    SELECT '山东医学高等专科学校' AS school_name, '临沂校区' AS name UNION ALL
    SELECT '山东医学高等专科学校', '济南校区' UNION ALL
    SELECT '菏泽医学专科学校', '主校区' UNION ALL
    SELECT '山东商业职业技术学院', '旅游路校区' UNION ALL
    SELECT '山东商业职业技术学院', '经十路校区' UNION ALL
    SELECT '山东商业职业技术学院', '明水校区' UNION ALL
    SELECT '山东电力高等专科学校', '主校区' UNION ALL
    SELECT '日照职业技术学院', '主校区' UNION ALL
    SELECT '曲阜远东职业技术学院', '主校区' UNION ALL
    SELECT '青岛职业技术学院', '西校区' UNION ALL
    SELECT '青岛职业技术学院', '南校区' UNION ALL
    SELECT '威海职业学院', '主校区' UNION ALL
    SELECT '山东职业学院', '主校区' UNION ALL
    SELECT '山东职业学院', '章丘校区' UNION ALL
    SELECT '山东劳动职业技术学院', '槐荫校区' UNION ALL
    SELECT '山东劳动职业技术学院', '长清校区' UNION ALL
    SELECT '莱芜职业技术学院', '主校区' UNION ALL
    SELECT '济宁职业技术学院', '主校区' UNION ALL
    SELECT '潍坊职业学院', '主校区' UNION ALL
    SELECT '烟台职业学院', '福山校区' UNION ALL
    SELECT '烟台职业学院', '开发区校区' UNION ALL
    SELECT '东营职业学院', '主校区' UNION ALL
    SELECT '聊城职业技术学院', '主校区' UNION ALL
    SELECT '滨州职业学院', '主校区' UNION ALL
    SELECT '山东科技职业学院', '主校区' UNION ALL
    SELECT '山东服装职业学院', '主校区' UNION ALL
    SELECT '德州科技职业学院', '禹城校区' UNION ALL
    SELECT '山东力明科技职业学院', '主校区' UNION ALL
    SELECT '山东圣翰财贸职业学院', '主校区' UNION ALL
    SELECT '山东水利职业学院', '主校区' UNION ALL
    SELECT '山东畜牧兽医职业学院', '主校区' UNION ALL
    SELECT '青岛飞洋职业技术学院', '主校区' UNION ALL
    SELECT '东营科技职业学院', '主校区' UNION ALL
    SELECT '山东交通职业学院', '潍坊校区' UNION ALL
    SELECT '山东外贸职业学院', '主校区' UNION ALL
    SELECT '青岛酒店管理职业技术学院', '主校区' UNION ALL
    SELECT '山东信息职业技术学院', '主校区' UNION ALL
    SELECT '青岛港湾职业技术学院', '黄岛校区' UNION ALL
    SELECT '山东胜利职业学院', '主校区' UNION ALL
    SELECT '山东经贸职业学院', '主校区' UNION ALL
    SELECT '山东工业职业学院', '主校区' UNION ALL
    SELECT '山东化工职业学院', '主校区' UNION ALL
    SELECT '青岛求实职业技术学院', '主校区' UNION ALL
    SELECT '济南职业学院', '主校区' UNION ALL
    SELECT '烟台工程职业技术学院', '主校区' UNION ALL
    SELECT '潍坊工商职业学院', '诸城校区' UNION ALL
    SELECT '德州职业技术学院', '主校区' UNION ALL
    SELECT '枣庄科技职业学院', '滕州校区' UNION ALL
    SELECT '淄博师范高等专科学校', '主校区' UNION ALL
    SELECT '山东中医药高等专科学校', '烟台校区' UNION ALL
    SELECT '济南工程职业技术学院', '主校区' UNION ALL
    SELECT '山东电子职业技术学院', '主校区' UNION ALL
    SELECT '山东电子职业技术学院', '章丘校区' UNION ALL
    SELECT '山东旅游职业学院', '主校区' UNION ALL
    SELECT '山东铝业职业学院', '主校区' UNION ALL
    SELECT '山东杏林科技职业学院', '主校区' UNION ALL
    SELECT '泰山职业技术学院', '主校区' UNION ALL
    SELECT '山东药品食品职业学院', '威海校区' UNION ALL
    SELECT '山东商务职业学院', '烟台校区' UNION ALL
    SELECT '山东轻工职业学院', '淄博校区' UNION ALL
    SELECT '山东城市建设职业学院', '主校区' UNION ALL
    SELECT '烟台汽车工程职业学院', '福山校区' UNION ALL
    SELECT '山东司法警官职业学院', '主校区' UNION ALL
    SELECT '菏泽家政职业学院', '单县校区' UNION ALL
    SELECT '山东传媒职业学院', '主校区' UNION ALL
    SELECT '临沂职业学院', '主校区' UNION ALL
    SELECT '枣庄职业学院', '主校区' UNION ALL
    SELECT '山东理工职业学院', '主校区' UNION ALL
    SELECT '山东文化产业职业学院', '主校区' UNION ALL
    SELECT '青岛远洋船员职业学院', '黄岛校区' UNION ALL
    SELECT '济南幼儿师范高等专科学校', '主校区' UNION ALL
    SELECT '济南护理职业学院', '主校区' UNION ALL
    SELECT '泰山护理职业学院', '主校区' UNION ALL
    SELECT '山东海事职业学院', '潍坊校区' UNION ALL
    SELECT '潍坊护理职业学院', '主校区' UNION ALL
    SELECT '潍坊工程职业学院', '青州校区' UNION ALL
    SELECT '菏泽职业学院', '主校区' UNION ALL
    SELECT '山东艺术设计职业学院', '主校区' UNION ALL
    SELECT '威海海洋职业学院', '荣成校区' UNION ALL
    SELECT '山东特殊教育职业学院', '主校区' UNION ALL
    SELECT '烟台黄金职业学院', '招远校区' UNION ALL
    SELECT '日照航海工程职业学院', '主校区' UNION ALL
    SELECT '青岛工程职业学院', '主校区' UNION ALL
    SELECT '青岛幼儿师范高等专科学校', '主校区' UNION ALL
    SELECT '烟台幼儿师范高等专科学校', '主校区' UNION ALL
    SELECT '烟台文化旅游职业学院', '主校区' UNION ALL
    SELECT '临沂科技职业学院', '主校区' UNION ALL
    SELECT '青岛航空科技职业学院', '主校区' UNION ALL
    SELECT '潍坊环境工程职业学院', '主校区' UNION ALL
    SELECT '滨州科技职业学院', '主校区' UNION ALL
    SELECT '山东城市服务职业学院', '主校区' UNION ALL
    SELECT '潍坊食品科技职业学院', '主校区' UNION ALL
    SELECT '烟台城市科技职业学院', '主校区' UNION ALL
    SELECT '德州工程职业学院', '主校区' UNION ALL
    SELECT '日照康养职业学院', '主校区' UNION ALL
    SELECT '烟台卫生健康职业学院', '主校区' UNION ALL
    SELECT '山东文化艺术职业学院', '主校区' UNION ALL
    SELECT '聊城科技职业学院', '主校区' UNION ALL
    SELECT '济宁汽车工程职业学院', '主校区' UNION ALL
    SELECT '日照科技职业学院', '主校区' UNION ALL
    SELECT '枣庄应用技术职业学院', '主校区' UNION ALL
    SELECT '临沂城市职业学院', '主校区' UNION ALL
    SELECT '菏泽生物医药职业学院', '主校区'
) c ON c.school_name = s.name;
