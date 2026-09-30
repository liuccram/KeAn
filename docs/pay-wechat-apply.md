# 课安申请流程 + 微信支付（后续接入草案）

当前平台**不代收**，酬谢只展示、线下结算。若后续改为「申请者扫微信支付码，服务端听微信回调」，建议按下面改，尽量少动现有状态机。

## 现在怎么走

```
发布 WAITING
  → 有人申请 APPLYING（申请 PENDING）
  → 发布者接受一人 MATCHED（其余 REJECTED）
  → 双方确认履约 CONFIRMED
  → 到上课时间 IN_PROGRESS
  → 双方确认完成 COMPLETED
```

申请接口：`POST /api/tasks/{id}/applications`。撤回、拒绝、接受逻辑保持不变。

## 钱放在哪一步（推荐）

**不要在点「申请」时扣款。** 多人申请会造成大量未中选退款。

推荐：**发布者选中之后，被选中的申请人付款。**

```
… → 发布者 accept
      任务进入 MATCHED_UNPAID（新增，未付款不算撮合完成）
      后端向微信 Native 下单，返回 code_url
      申请人页展示二维码 / 调起微信支付
      微信 notify_url 回调验签、幂等入账
      任务才进入现有 MATCHED
      之后确认 / 上课 / 完成 与现在相同
```

未支付超时（建议 15 分钟）自动关单：任务回到 `APPLYING`，该申请回到 `PENDING`（或记 `PAY_EXPIRED` 后允许重新拉码）。匹配后取消：已支付则走微信退款，未支付只关单。

## 申请侧要动什么

现有 `substitute_application` **不必拆表**。建议：

| 改动 | 说明 |
|---|---|
| 申请表加 `pay_status` | `NONE` / `WAITING` / `PAID` / `CLOSED` / `REFUNDED` |
| 新增 `pay_order` | `out_trade_no`、`transaction_id`、金额（用任务 `reward` 分）、`code_url`、状态 |
| 任务状态加 `MATCHED_UNPAID` | 只插在 accept 与 MATCHED 之间 |
| `accept` | 不再立刻 MATCHED；创建订单并返回支付码 |
| 新接口 `GET/POST .../pay` | 查单、重新拉码（未支付才能） |
| `POST /api/pay/wechat/notify` | **匿名**、验签、按 `out_trade_no` 幂等；成功才 `MATCHED` |
| 移动端详情 | `MATCHED_UNPAID` 且自己是被选中人时展示二维码，并短轮询或 WS 刷新 |

发布者、管理员、消息文案：接受后提示「等待对方完成微信支付」，付款成功再发「申请已被接受」那条现有通知。

`reward = 0` 的任务跳过支付，accept 仍直接 `MATCHED`。

## 不要先做的

微信 AppId / 商户号 / APIv3 密钥、证书、公网 HTTPS 回调域名。未开通前保持线下结算，前端继续写「平台不代收」。

## 备选：申请即付

若产品坚持「点申请就扫码」，申请先落 `PENDING_PAY`，回调成功才算 `PENDING` 并 `applyCount+1`。未中选必须退款。实现更重，不推荐作为第一期。
