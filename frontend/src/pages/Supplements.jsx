import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import {
  Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta,
  CHANGE_TYPE, COST_BEARING, COST_BEARING_TONE
} from '../ui.jsx'

const blankItem = () => ({ materialId: '', applyQty: '', usage: '' })

export default function Supplements() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const canCreate = ['ROLE_FOREMAN', 'ROLE_KEEPER', 'ROLE_PM', 'ROLE_BUDGET'].includes(user.role)
  const canReview = ['ROLE_PM', 'ROLE_BUDGET'].includes(user.role)

  const [rows, setRows] = useState([])
  const [visas, setVisas] = useState([])
  const [status, setStatus] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [ctx, setCtx] = useState(null)
  const [detail, setDetail] = useState(null)
  const [review, setReview] = useState({ bearing: 'VISA', remark: '' })
  const [issueRows, setIssueRows] = useState({})

  const [form, setForm] = useState({
    visaId: '', crewId: user.crewId || '', building: '', area: '', process: '',
    drawingNo: '', reason: '', costBearing: 'VISA', laborCost: '', items: [blankItem()]
  })

  const load = () => {
    const q = new URLSearchParams()
    if (status) q.set('status', status)
    const cid = user.role === 'ROLE_FOREMAN' ? user.crewId : ''
    if (cid) q.set('crewId', cid)
    api.get('/api/supplements?' + q.toString()).then(setRows).catch(() => setRows([]))
    api.get('/api/visas').then(setVisas).catch(() => {})
  }
  useEffect(() => { load() }, [status])

  const usableVisas = visas.filter((v) => v.status === 'CONFIRMED' || v.status === 'CLAIMED')

  const pickVisa = (visaId) => {
    const v = visas.find((x) => String(x.id) === String(visaId))
    setForm((f) => ({
      ...f,
      visaId,
      crewId: v?.crewId || f.crewId,
      building: v?.building || '',
      area: v?.area || '',
      process: v?.process || '',
      drawingNo: v?.drawingNo || '',
      items: v?.materialId
        ? [{ materialId: v.materialId, applyQty: '', usage: '' }]
        : f.items
    }))
  }

  const submitCreate = async () => {
    if (!form.visaId || !form.crewId || !form.process) return toast('请选择变更签证、班组并填写工序', 'err')
    if (form.items.some((i) => !i.materialId || !i.applyQty)) return toast('请完善追加材料明细', 'err')
    try {
      await api.post('/api/supplements', {
        visaId: Number(form.visaId),
        crewId: Number(form.crewId),
        building: form.building, area: form.area, process: form.process, drawingNo: form.drawingNo,
        reason: form.reason, costBearing: form.costBearing,
        laborCost: form.laborCost === '' ? 0 : Number(form.laborCost),
        items: form.items.map((i) => ({
          materialId: Number(i.materialId), applyQty: Number(i.applyQty), usage: i.usage
        }))
      })
      toast('变更追加领料申请已提交，待项目经理审核')
      setShowCreate(false)
      setForm({ ...form, visaId: '', items: [blankItem()] })
      load()
    } catch (e) { toast(e.message, 'err') }
  }

  const openContext = async (s) => {
    try {
      const data = await api.get(`/api/supplements/${s.id}/context`)
      setCtx(data)
      setReview({ bearing: data.supplement.costBearing || 'VISA', remark: '' })
      const init = {}
      data.checks.forEach((c) => {
        init[c.materialId] = { batchId: c.batches[0]?.batchId || '', issuedQty: c.applyQty }
      })
      setIssueRows(init)
    } catch (e) { toast(e.message, 'err') }
  }

  const doReview = async (approved) => {
    if (!ctx) return
    if (!approved && !review.remark) return toast('驳回请填写审核意见', 'err')
    try {
      await api.put(`/api/supplements/${ctx.supplement.id}/review`, {
        approved, costBearing: review.bearing, reviewRemark: review.remark
      })
      toast(approved ? '审核通过，已生成追加领料单（仓管可发料）' : '已驳回')
      setCtx(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const doIssue = async () => {
    // 通过追加单上下文直接发料：找到追加领料单明细
    try {
      const req = await api.get(`/api/requisitions/${ctx.supplement.requisitionId}/precheck`)
      const items = req.checks.map((c) => ({
        itemId: c.itemId,
        batchId: Number(issueRows[c.materialId]?.batchId),
        issuedQty: Number(issueRows[c.materialId]?.issuedQty)
      }))
      if (items.some((i) => !i.batchId || !i.issuedQty)) return toast('请为每条材料选择批次并填写实发数量', 'err')
      await api.put(`/api/requisitions/${ctx.supplement.requisitionId}/issue`, { items })
      toast('追加领料发料成功，新增材料已与变更编号绑定')
      setCtx(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const goRequisitions = () => { window.location.hash = '#/requisitions' }

  const totalMaterial = rows.reduce((a, r) => a + (r.materialAmount || 0), 0)
  const totalLabor = rows.reduce((a, r) =>
    a + (r.status === 'APPROVED' || r.status === 'ISSUED' ? (r.laborCost || 0) : 0), 0)

  return (
    <div>
      <Card title="变更追加领料（设计/施工变更 → 材料与人工追加）"
            hint={`关联设计变更、原预算、已领材料与新增需求；PM 审核判定成本承担方后生成追加领料单。已发料追加材料 ${fmt(totalMaterial)} 元，追加人工 ${fmt(totalLabor)} 元`}
            actions={
              <div className="filters">
                <div className="f">
                  <label>状态</label>
                  <select value={status} onChange={(e) => setStatus(e.target.value)}>
                    <option value="">全部</option>
                    <option value="SUBMITTED">待审核</option>
                    <option value="APPROVED">已审核·待发料</option>
                    <option value="ISSUED">已发料</option>
                    <option value="REJECTED">已驳回</option>
                  </select>
                </div>
                {canCreate && <button className="btn primary" onClick={() => setShowCreate(true)}>＋ 发起变更追加领料</button>}
              </div>
            } flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>追加单号</th><th>变更签证</th><th>楼栋/工序</th><th>班组</th>
              <th>追加材料</th><th className="num">材料费</th><th className="num">追加人工</th>
              <th>成本承担</th><th>状态</th><th>申请人/审核人</th><th>操作</th>
            </tr></thead>
            <tbody>
            {rows.map((s) => (
              <tr key={s.id}>
                <td className="code-link" onClick={() => setDetail(s)}>{s.code}</td>
                <td><b>{s.visaCode}</b>
                  <div className="muted" style={{ fontSize: 12 }}>{CHANGE_TYPE[s.visaChangeType]}</div></td>
                <td>{s.building} {s.area}
                  <div className="muted" style={{ fontSize: 12 }}>{s.process} · {s.drawingNo}</div></td>
                <td>{s.crewName}</td>
                <td style={{ fontSize: 12.5 }}>{s.items.map((i) =>
                  `${i.materialName}×${fmt(i.issuedQty || i.applyQty, 0)}`).join('，')}</td>
                <td className="num">{fmt(s.materialAmount)}</td>
                <td className="num">{fmt(s.laborCost)}</td>
                <td><Badge status="DRAFT" label={COST_BEARING[s.costBearing]}
                           tone={COST_BEARING_TONE[s.costBearing]} /></td>
                <td><Badge status={s.status} /></td>
                <td style={{ fontSize: 12 }}>{s.applicant}
                  <div className="muted">{s.reviewer || '—'}</div></td>
                <td>
                  {s.status === 'SUBMITTED' && canReview && (
                    <button className="btn sm primary" onClick={() => openContext(s)}>项目经理审核</button>
                  )}
                  {s.status === 'SUBMITTED' && !canReview &&
                    <span className="muted" style={{ fontSize: 12 }}>待项目经理审核</span>}
                  {s.status === 'APPROVED' && (
                    <div className="btn-row">
                      {user.role === 'ROLE_KEEPER' || user.role === 'ROLE_PM'
                        ? <button className="btn sm primary" onClick={() => openContext(s)}>仓管发料</button>
                        : <span className="muted" style={{ fontSize: 12 }}>待仓管发料</span>}
                    </div>
                  )}
                  {s.status === 'ISSUED' && <button className="btn sm ghost" onClick={() => setDetail(s)}>查看</button>}
                  {s.status === 'REJECTED' && <span className="muted" style={{ fontSize: 12 }}>已驳回</span>}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={11}><div className="empty">暂无变更追加领料申请</div></td></tr>
            )}
            </tbody>
          </table>
        </div>
      </Card>

      {/* 新建追加申请 */}
      {showCreate && (
        <Modal title="发起变更追加领料" wide onClose={() => setShowCreate(false)}
               footer={<><button className="btn" onClick={() => setShowCreate(false)}>取消</button>
                 <button className="btn primary" onClick={submitCreate}>提交追加申请</button></>}>
          <div className="grid3">
            <Field label="关联设计/施工变更签证 *">
              <select value={form.visaId} onChange={(e) => pickVisa(e.target.value)}>
                <option value="">请选择已确认签证</option>
                {usableVisas.map((v) => <option key={v.id} value={v.id}>
                  {v.code}｜{v.building} {v.process}｜{CHANGE_TYPE[v.changeType]}
                </option>)}
                {usableVisas.length === 0 && visas.filter((v) => v.status === 'DRAFT').length > 0 && (
                  <option value="" disabled>存在草稿签证，请先在「变更签证」中确认</option>
                )}
              </select>
            </Field>
            <Field label="责任班组 *">
              <select value={form.crewId} disabled={user.role === 'ROLE_FOREMAN'}
                      onChange={(e) => setForm({ ...form, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="成本初判（PM 终核）*">
              <select value={form.costBearing} onChange={(e) => setForm({ ...form, costBearing: e.target.value })}>
                <option value="VISA">可向甲方签证（进索赔清单）</option>
                <option value="CREW">施工返工（计入责任班组）</option>
                <option value="COMPANY">项目承担</option>
              </select>
            </Field>
            <Field label="楼栋 *"><input value={form.building}
              onChange={(e) => setForm({ ...form, building: e.target.value })} readOnly={!!form.visaId} /></Field>
            <Field label="施工区域"><input value={form.area} onChange={(e) => setForm({ ...form, area: e.target.value })} readOnly={!!form.visaId} /></Field>
            <Field label="工序 *"><input value={form.process} onChange={(e) => setForm({ ...form, process: e.target.value })} readOnly={!!form.visaId} /></Field>
            <Field label="图纸编号"><input value={form.drawingNo} onChange={(e) => setForm({ ...form, drawingNo: e.target.value })} readOnly={!!form.visaId} /></Field>
            <Field label="追加人工费（元）"><input type="number" value={form.laborCost}
              onChange={(e) => setForm({ ...form, laborCost: e.target.value })} placeholder="如附加层施工人工 3600" /></Field>
          </div>
          <Field label="追加事由"><textarea rows={2} value={form.reason}
            onChange={(e) => setForm({ ...form, reason: e.target.value })}
            placeholder="例：设计临时调整卫生间防水上翻高度，由300mm提高至1800mm，需追加卷材及附加层人工" /></Field>

          <div style={{ margin: '8px 0 6px', fontWeight: 600 }}>新增材料需求</div>
          <table className="tbl">
            <thead><tr><th>材料</th><th style={{ width: 130 }}>追加数量</th><th>用途/部位</th><th style={{ width: 60 }}></th></tr></thead>
            <tbody>
            {form.items.map((it, idx) => (
              <tr key={idx}>
                <td>
                  <select value={it.materialId} onChange={(e) => {
                    const items = form.items.slice()
                    items[idx] = { ...items[idx], materialId: e.target.value }
                    setForm({ ...form, items })
                  }}>
                    <option value="">请选择材料</option>
                    {meta.materials.map((m) => <option key={m.id} value={m.id}>
                      [{m.category}] {m.name}（{m.unit}）</option>)}
                  </select>
                </td>
                <td><input type="number" value={it.applyQty} onChange={(e) => {
                  const items = form.items.slice()
                  items[idx] = { ...items[idx], applyQty: e.target.value }
                  setForm({ ...form, items })
                }} /></td>
                <td><input value={it.usage} placeholder="如：卫生间墙面附加层" onChange={(e) => {
                  const items = form.items.slice()
                  items[idx] = { ...items[idx], usage: e.target.value }
                  setForm({ ...form, items })
                }} /></td>
                <td>{form.items.length > 1 && (
                  <button className="btn sm danger" onClick={() =>
                    setForm({ ...form, items: form.items.filter((_, i) => i !== idx) })}>删</button>
                )}</td>
              </tr>
            ))}
            </tbody>
          </table>
          <button className="btn" style={{ marginTop: 10 }}
                  onClick={() => setForm({ ...form, items: [...form.items, blankItem()] })}>＋ 增加材料</button>
        </Modal>
      )}

      {/* PM 审核 / 仓管发料 上下文 */}
      {ctx && (
        <Modal wide title={ctx.supplement.status === 'SUBMITTED'
            ? `变更追加审核 · ${ctx.supplement.code}`
            : `追加领料发料 · ${ctx.supplement.code}（领料单 ${ctx.supplement.requisitionCode}）`}
          onClose={() => setCtx(null)}
          footer={ctx.supplement.status === 'SUBMITTED' ? (
            <><button className="btn" onClick={() => setCtx(null)}>取消</button>
              <button className="btn danger" onClick={() => doReview(false)}>驳回（班组补充）</button>
              <button className="btn primary" onClick={() => doReview(true)}>审核通过，生成追加领料单</button></>
          ) : (
            <><button className="btn" onClick={() => setCtx(null)}>取消</button>
              <button className="btn primary" onClick={doIssue}>确认发料（绑定变更编号）</button></>
          )}>
          <div className="kpi-note" style={{ marginBottom: 10, lineHeight: 1.9 }}>
            <div>关联变更：<b>{ctx.visa?.code}</b>（{CHANGE_TYPE[ctx.visa?.changeType]}）· {ctx.supplement.building} {ctx.supplement.area} ·
              <b> {ctx.supplement.process}</b> · 图纸 {ctx.supplement.drawingNo} · 班组 <b>{ctx.supplement.crewName}</b></div>
            <div>签证内容：{ctx.visa?.description}</div>
            <div>追加事由：{ctx.supplement.reason}</div>
          </div>

          <div className="table-scroll">
            <table className="tbl">
              <thead><tr>
                <th>新增材料</th><th className="num">追加量</th>
                <th className="num">原预算量</th><th className="num">已领量</th>
                <th className="num">现场库存</th><th>库存核对</th>
                {ctx.supplement.status === 'APPROVED' && (<>
                  <th>发料批次</th><th className="num">实发数量</th>
                </>)}
              </tr></thead>
              <tbody>
              {ctx.checks.map((c) => (
                <tr key={c.itemId}>
                  <td><b>{c.materialName}</b>
                    <div className="muted" style={{ fontSize: 12 }}>{c.spec} · 单位 {c.unit}</div></td>
                  <td className="num"><b>{fmt(c.applyQty)}</b></td>
                  <td className="num">{fmt(c.budgetQty)}</td>
                  <td className="num">{fmt(c.alreadyIssued)}</td>
                  <td className="num">{fmt(c.onHandQty)}</td>
                  <td>{c.stockEnough
                    ? <Badge status="PASSED" label="库存充足" />
                    : <Badge status="FAILED" label="库存不足" />}</td>
                  {ctx.supplement.status === 'APPROVED' && (<>
                    <td style={{ minWidth: 200 }}>
                      <select value={issueRows[c.materialId]?.batchId || ''}
                              onChange={(e) => setIssueRows({ ...issueRows,
                                [c.materialId]: { ...issueRows[c.materialId], batchId: e.target.value } })}>
                        <option value="">选择批次</option>
                        {c.batches.map((b) => (
                          <option key={b.batchId} value={b.batchId}>
                            {b.batchNo}｜库存{fmt(b.onHand)}｜{fmt(b.unitPrice)}元
                          </option>
                        ))}
                      </select>
                    </td>
                    <td><input type="number" value={issueRows[c.materialId]?.issuedQty ?? ''}
                      onChange={(e) => setIssueRows({ ...issueRows,
                        [c.materialId]: { ...issueRows[c.materialId], issuedQty: e.target.value } })} /></td>
                  </>)}
                </tr>
              ))}
              </tbody>
            </table>
          </div>

          {ctx.issuedHistory?.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <div style={{ fontWeight: 600, marginBottom: 6 }}>该班组同楼栋/工序已领材料</div>
              <table className="tbl">
                <thead><tr><th>领料单</th><th>类型</th><th>材料与数量</th><th>发料时间</th></tr></thead>
                <tbody>
                {ctx.issuedHistory.map((h) => (
                  <tr key={h.requisitionId}>
                    <td>{h.code}</td>
                    <td>{h.costBearing
                      ? <Badge status="DRAFT" label={COST_BEARING[h.costBearing]} tone={COST_BEARING_TONE[h.costBearing]} />
                      : <span className="muted">合同内</span>}</td>
                    <td style={{ fontSize: 12.5 }}>{h.items.map((i) =>
                      `${i.materialName}×${fmt(i.issuedQty, 0)}`).join('，')}</td>
                    <td>{fmtTime(h.issueTime)}</td>
                  </tr>
                ))}
                </tbody>
              </table>
            </div>
          )}

          {ctx.supplement.status === 'SUBMITTED' && (
            <div className="grid2" style={{ marginTop: 12 }}>
              <Field label="成本承担方终核 *">
                <select value={review.bearing} onChange={(e) => setReview({ ...review, bearing: e.target.value })}>
                  <option value="VISA">可向甲方签证 —— 材料+人工进入变更索赔清单（合同外）</option>
                  <option value="CREW">施工返工 —— 材料+人工计入责任班组月度扣款</option>
                  <option value="COMPANY">项目承担 —— 不索赔、不扣班组</option>
                </select>
              </Field>
              <Field label="审核意见"><input value={review.remark}
                onChange={(e) => setReview({ ...review, remark: e.target.value })}
                placeholder="通过可留空；驳回需说明原因" /></Field>
            </div>
          )}
          <div className="kpi-note" style={{ marginTop: 10 }}>
            审核通过后自动生成追加领料单（单号 LZ 开头），平台把新增材料与变更编号 {ctx.visa?.code} 绑定；
            财务月底核算直接区分<b>合同内成本</b>与<b>签证成本</b>，返工成本计入责任班组。
          </div>
        </Modal>
      )}

      {/* 详情 */}
      {detail && (
        <Modal title={`变更追加领料 ${detail.code}`} wide onClose={() => setDetail(null)}
               footer={<button className="btn primary" onClick={() => setDetail(null)}>关闭</button>}>
          <div className="kpi-note" style={{ marginBottom: 10, lineHeight: 1.9 }}>
            <div>变更签证：<b>{detail.visaCode}</b>（{CHANGE_TYPE[detail.visaChangeType]}）
              状态：<Badge status={detail.status} />
              成本承担：<Badge status="DRAFT" label={COST_BEARING[detail.costBearing]}
                              tone={COST_BEARING_TONE[detail.costBearing]} /></div>
            <div>{detail.building} {detail.area} · {detail.process} · {detail.drawingNo} · {detail.crewName}</div>
            <div>追加事由：{detail.reason}</div>
            <div>追加领料单：{detail.requisitionCode
              ? <b>{detail.requisitionCode}</b>
              : <span className="muted">审核通过后生成</span>}　申请人：{detail.applicant}　审核人：{detail.reviewer || '—'}</div>
            {detail.reviewRemark && <div>审核意见：{detail.reviewRemark}</div>}
          </div>
          <table className="tbl">
            <thead><tr><th>材料</th><th className="num">追加量</th><th className="num">实发量</th>
              <th>批次</th><th className="num">单价</th><th className="num">金额</th><th>用途</th></tr></thead>
            <tbody>
            {detail.items.map((i, idx) => (
              <tr key={idx}>
                <td>{i.materialName}</td>
                <td className="num">{fmt(i.applyQty)} {i.unit}</td>
                <td className="num">{fmt(i.issuedQty)} {i.unit}</td>
                <td>{i.batchNo || '—'}</td>
                <td className="num">{fmt(i.issuePrice)}</td>
                <td className="num">{fmt(i.amount)}</td>
                <td className="muted" style={{ fontSize: 12 }}>{i.usage || '—'}</td>
              </tr>
            ))}
            </tbody>
          </table>
          <div className="right" style={{ marginTop: 10, lineHeight: 2 }}>
            追加材料合计：<b>{fmt(detail.materialAmount)}</b> 元<br/>
            追加人工：<b>{fmt(detail.laborCost)}</b> 元<br/>
            合计：<b style={{ fontSize: 15 }}>{fmt(detail.totalAmount)}</b> 元
            {detail.costBearing === 'VISA' && <span className="muted">（进入变更索赔清单）</span>}
            {detail.costBearing === 'CREW' && <span className="muted">（计入责任班组）</span>}
          </div>
        </Modal>
      )}
    </div>
  )
}
