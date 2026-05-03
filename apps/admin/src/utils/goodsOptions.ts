export type GoodsModuleKey = 'base' | 'media' | 'integration'

export const platformOptions = [
  { label: '抖音', value: 'douyin', logo: 'douyin' },
  { label: '淘宝', value: 'taobao', logo: 'taobao' },
  { label: '拼多多', value: 'pdd', logo: 'pdd' },
  { label: '咸鱼', value: 'xianyu', logo: 'xianyu' },
  { label: '小红书', value: 'xiaohongshu', logo: 'xiaohongshu' }
]

export const statusOptions = [
  { label: '上架', value: 'ON_SALE', type: 'success' },
  { label: '下架', value: 'OFF_SALE', type: 'info' },
  { label: '售罄', value: 'SOLD_OUT', type: 'warning' }
]

export const deliveryOptions = [
  { label: '卡密', value: 'CARD' },
  { label: '代充', value: 'MANUAL' },
  { label: '直充', value: 'DIRECT' }
]

export const benefitDurationOptions = ['一天', '三天', '周卡', '半月', '月卡', '季卡', '半年', '一年']

export const fallbackAccountTypeOptions = [
  { label: '手机号', value: 'mobile' },
  { label: 'QQ号', value: 'qq' },
  { label: '剪映ID', value: 'jianying_id' },
  { label: '抖音ID', value: 'douyin_id' },
  { label: '微信号', value: 'wechat' },
  { label: '邮箱', value: 'email' },
  { label: '游戏 UID', value: 'game_uid' }
]

export const monitoringItems = ['价格', '库存', '商品状态', '上游标题']

export const goodsModules: { key: GoodsModuleKey; title: string; desc: string }[] = [
  { key: 'base', title: '基础资料与价格', desc: '类型、分类、售价、库存、平台、充值字段' },
  { key: 'media', title: '主图展示', desc: '商品主图上传' },
  { key: 'integration', title: '对接监控', desc: '上游商品、轮询、监听范围' }
]
