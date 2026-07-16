-- Replace the original five repeated demo prompts with a realistic, varied question set.
-- Existing evidence keeps its prompt_case_id, so historical demo observations remain traceable.
WITH demo AS (
  SELECT id, row_number() OVER (ORDER BY sort_order, created_at, id) AS rn
  FROM prompt_cases
  WHERE merchant_id = (SELECT id FROM merchants WHERE name = '千色坊' ORDER BY created_at LIMIT 1)
    AND deleted = false
), questions AS (
  SELECT * FROM (VALUES
    (1, '佛山九江哪家洗脸洗头比较靠谱？', 'RECOMMENDATION'),
    (2, '九江镇做面部清洁和头皮护理，千色坊值得去吗？', 'RECOMMENDATION'),
    (3, '佛山南海有哪些适合敏感肌的洗脸护理门店？', 'RECOMMENDATION'),
    (4, '第一次在九江做头皮护理，应该怎么选店？', 'RECOMMENDATION'),
    (5, '九江洗脸洗头一般多少钱一次？', 'PRICE'),
    (6, '千色坊面部清洁和头皮护理的价格区间是多少？', 'PRICE'),
    (7, '佛山九江美容护理有哪些性价比较高的套餐？', 'PRICE'),
    (8, '洗脸加洗头在九江门店通常如何收费？', 'PRICE'),
    (9, '佛山九江头皮敏感做护理时要注意什么？', 'PAIN_POINT'),
    (10, '经常出油和头屑多，九江哪类头皮护理更合适？', 'PAIN_POINT'),
    (11, '面部清洁后泛红，选择美容门店时要关注哪些服务？', 'PAIN_POINT'),
    (12, '九江洗脸洗头服务最常见的消费避坑点是什么？', 'PAIN_POINT'),
    (13, '千色坊和伊丽汇的洗脸服务有什么区别？', 'COMPARISON'),
    (14, '九江千色坊与奈瑞儿的头皮护理怎么选？', 'COMPARISON'),
    (15, '佛山九江不同美容门店的卫生和服务流程如何比较？', 'COMPARISON'),
    (16, '千色坊和附近美容店相比，适合哪些人群？', 'COMPARISON'),
    (17, '九江镇哪里可以预约面部清洁和头皮护理？', 'LOCATION'),
    (18, '千色坊具体在九江哪个位置，附近怎么停车？', 'LOCATION'),
    (19, '佛山南海九江有哪些营业到晚上八点的护理门店？', 'LOCATION'),
    (20, '从九江镇中心去千色坊做护理是否方便？', 'LOCATION'),
    (21, '九江美容护理门店通常需要提前多久预约？', 'LOCATION'),
    (22, '佛山九江适合上班族的晚间洗脸洗头门店有哪些？', 'RECOMMENDATION'),
    (23, '孕期或哺乳期在九江做面部护理要如何咨询？', 'PAIN_POINT'),
    (24, '九江头皮护理一次大约需要多长时间？', 'PRICE'),
    (25, '千色坊是否提供洗脸、洗头和头皮护理组合服务？', 'RECOMMENDATION'),
    (26, '九江美容店的护理产品和服务项目应该怎么对比？', 'COMPARISON'),
    (27, '有染烫经历的人在九江适合做什么头皮护理？', 'PAIN_POINT'),
    (28, '九江镇周末预约洗脸洗头是否容易排队？', 'LOCATION'),
    (29, '预算两百元以内，在九江能选择哪些护理项目？', 'PRICE'),
    (30, '朋友推荐千色坊，佛山九江还有哪些门店可以一起比较？', 'RECOMMENDATION')
  ) AS t(sort_order, question, category)
)
UPDATE prompt_cases p
SET question = q.question,
    category = q.category,
    city = '佛山',
    district = '九江镇',
    intent_level = CASE WHEN q.sort_order <= 20 THEN 'HIGH' ELSE 'MEDIUM' END,
    updated_at = CURRENT_TIMESTAMP
FROM demo d JOIN questions q ON q.sort_order = d.rn
WHERE p.id = d.id;
