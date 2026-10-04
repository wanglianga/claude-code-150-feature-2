import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta,
  CHANGE_TYPE, COST_BEARING_SHORT } from '../ui.jsx'

export default function Visas() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const canCreate = ['ROLE_PM', 'ROLE_BUDGET', 'ROLE_KEEPER'].includes(user.role)
  const canConfirm = ['ROLE_PM', 'ROLE_BUDGET'].includes(user.role)
  const canClaim = user.role === 'ROLE_FINANCE'
  // 发起变更追加领料：班组长/仓管/项目经理/预算员
  const canAdd = ['ROLE_FOREMAN', 'ROLE_KEEPER', 'ROLE_PM', 'ROLE_BUDGET'].includes(user.role)

  const [rows, setRows] = useState([])
  const [show, setShow] = useState(false)
  const [f, setF] = useState({
    building: '1#楼', area: '', process: '', drawingNo: '', materialId: '', crewId: '',
    changeType: 'DESIGN', description: '', qtyChange: '', unitPrice: '', claimAmount: ''
  })
  // 追加领料弹窗
  const [addVisa, setAddVisa] = useState(null)
  const [ctx, setCtx] = useState(null)
  const blankLine = () => ({ materialId: '', applyQty: '' })
  const [af, setAf] = useState({
    crewId: '', costBearing: 'VISA_CLAIM', laborQty: '', laborPrice: 200, remark: '',
    items: [blankLine()]
  })

  const load = () => api.get('/api/visas').then(setRows)
  useEffect(() => { load() }, [])

  const submit = async () => {
    try {
      await api.post('/api/visas', {
        ...f,
        materialId: f.materialId ? Number(f.materialId) : null,
        crewId: f.crewId ? Number(f.crewId) : null,
        qtyChange: f.qtyChange === '' ? 0 : Number(f.qtyChange),
        unitPrice: f.unitPrice === '' ? 0 : Number(f.unitPrice),
        claimAmount: f.claimAmount === '' ? undefined : Number(f.claimAmount)
      })
      toast('签证已登记'); setShow(false); load()
    } catch (e) { toast(e.message, 'err') }
  }
  const confirm = async (v) => { await api.put(`/api/visas/${v.id}/confirm`, {}); toast('签证已确认'); load() }
  const claim = async (v) => {
    if (!window.confirm(`确认签证 ${v.code} 的索赔已向业主/供方申报？`)) return
    await api.put(`/api/visas/${v.id}/claim`, {}); toast('已标记索赔'); load()
  }

  // 打开追加领料弹窗：拉取该变更关联的原预算/已领材料/现场库存/已有追加
  const openAdd = async (v) => {
    try {
      const data = await api.get(`/api/visas/${v.id}/additional-context`)
      setAddVisa(v); setCtx(data)
      setAf({
        crewId: v.crewId ? String(v.crewId) : (user.crewId ? String(user.crewId) : ''),
        costBearing: 'DESIGN' === v.changeType ? 'VISA_CLAIM' : 'REWORK',
        laborQty: '', laborPrice: 200,
        remark: `${v.description ? v.description.slice(0, 40) : ''}，追加材料与人工`,
        items: [{ materialId: v.materialId ? String(v.materialId) : '', applyQty: v.qtyChange > 0 ? String(v.qtyChange) : '' }]
      })
    } catch (e) { toast(e.message, 'err') }
  }

  const submitAdd = async () => {
    if (!af.crewId) return toast('请选择追加领料班组', 'err')
    const items = af.items.filter((i) => i.materialId && i.applyQty)
    if (!items.length) return toast('请至少填写一条追加材料', 'err')
    try {
      await api.post('/api/requisitions/additional', {
        visaId: addVisa.id,
        crewId: Number(af.crewId),
        building: addVisa.building,
        area: addVisa.area,
        process: addVisa.process,
        drawingNo: addVisa.drawingNo,
        costBearing: af.costBearing,
        extraLaborQty: af.laborQty === '' ? 0 : Number(af.laborQty),
        extraLaborPrice: af.laborPrice === '' ? 0 : Number(af.laborPrice),
        remark: af.remark,
        items: items.map((i) => ({ materialId: Number(i.materialId), applyQty: Number(i.applyQty) }))
      })
      toast('追加领料已提交，等待项目经理审核')
      setAddVisa(null); setCtx(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  // 弹窗内人工金额预估
  const laborEstimate = (Number(af.laborQty) || 0) * (Number(af.laborPrice) || 0)

  const total = rows.filter((v) => v.status !== 'DRAFT').reduce((s, v) => s + (v.claimAmount || 0), 0)

  return (
    <Card title={`变更签证（已确认索赔 ${fmt(total)} 元）`}
          hint="施工/设计变更引起的材料增减与费用；防水班组可据签证发起追加领料（卷材+人工），项目经理审核后发料并绑定变更编号"
          actions={canCreate && <button className="btn primary" onClick={() => setShow(true)}>＋ 登记变更签证</button>}
          flush>
      <div className="table-scroll">
        <table className="tbl">
          <thead><tr>
            <th>签证号</th><th>楼栋/工序</th><th>变更类型</th><th>涉及材料/班组</th>
            <th className="num">材料量变化</th><th className="num">索赔金额</th><th>状态</th>
            <th>内容</th><th>登记/确认时间</th><th>操作</th>
          </tr></thead>
          <tbody>
          {rows.map((v) => {
            const m = meta.materials.find((x) => x.id === v.materialId)
            const c = meta.crews.find((x) => x.id === v.crewId)
            return (
              <tr key={v.id}>
                <td className="code-link">{v.code}</td>
                <td>{v.building}<div className="muted" style={{ fontSize: 12 }}>{v.process} · {v.drawingNo}</div></td>
                <td><Badge status="DRAFT" label={CHANGE_TYPE[v.changeType]} tone="blue" /></td>
                <td>{m?.name || '—'}<div className="muted" style={{ fontSize: 12 }}>{c?.name || ''}</div></td>
                <td className="num">{v.qtyChange > 0 ? '+' : ''}{fmt(v.qtyChange)} {m?.unit}</td>
                <td className="num"><b>{fmt(v.claimAmount)}</b></td>
                <td><Badge status={v.status} /></td>
                <td className="desc-cell">{v.description}</td>
                <td style={{ fontSize: 12 }}>{fmtTime(v.createdTime)}<div className="muted">{fmtTime(v.confirmTime)}</div></td>
                <td>
                  <div className="btn-row">
                    {v.status === 'DRAFT' && canConfirm && <button className="btn sm primary" onClick={() => confirm(v)}>确认</button>}
                    {v.status === 'CONFIRMED' && canClaim && <button className="btn sm" onClick={() => claim(v)}>标记索赔</button>}
                    {canAdd && <button className="btn sm" onClick={() => openAdd(v)}>追加领料</button>}
                  </div>
                  {v.status === 'CLAIMED' && <span className="muted" style={{ fontSize: 12 }}>已索赔</span>}
                </td>
              </tr>
            )
          })}
          </tbody>
        </table>
      </div>

      {show && (
        <Modal title="登记变更签证" wide onClose={() => setShow(false)}
               footer={<><button className="btn" onClick={() => setShow(false)}>取消</button>
                 <button className="btn primary" onClick={submit}>保存签证</button></>}>
          <div className="grid3">
            <Field label="楼栋 *"><input value={f.building} onChange={(e) => setF({ ...f, building: e.target.value })} /></Field>
            <Field label="工序"><input value={f.process} onChange={(e) => setF({ ...f, process: e.target.value })} /></Field>
            <Field label="图纸编号"><input value={f.drawingNo} onChange={(e) => setF({ ...f, drawingNo: e.target.value })} /></Field>
            <Field label="变更类型 *">
              <select value={f.changeType} onChange={(e) => setF({ ...f, changeType: e.target.value })}>
                {Object.entries(CHANGE_TYPE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </Field>
            <Field label="涉及材料">
              <select value={f.materialId} onChange={(e) => setF({ ...f, materialId: e.target.value })}>
                <option value="">不涉及具体材料</option>
                {meta.materials.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
              </select>
            </Field>
            <Field label="涉及班组">
              <select value={f.crewId} onChange={(e) => setF({ ...f, crewId: e.target.value })}>
                <option value="">无</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="材料量变化（正增负减）"><input type="number" value={f.qtyChange}
              onChange={(e) => setF({ ...f, qtyChange: e.target.value })} /></Field>
            <Field label="材料单价"><input type="number" value={f.unitPrice}
              onChange={(e) => setF({ ...f, unitPrice: e.target.value })} /></Field>
            <Field label="索赔/费用金额（元）"><input type="number" value={f.claimAmount}
              onChange={(e) => setF({ ...f, claimAmount: e.target.value })} placeholder="留空按量×单价" /></Field>
          </div>
          <Field label="变更说明"><textarea rows={3} value={f.description}
            onChange={(e) => setF({ ...f, description: e.target.value })}
            placeholder="例：设计临时调整卫生间防水高度，淋浴区上翻由 300mm 加高至 1.8m，追加卷材并产生防水人工" /></Field>
        </Modal>
      )}

      {/* 变更追加领料 */}
      {addVisa && ctx && (
        <Modal title={`发起变更追加领料 · ${addVisa.code}`} wide onClose={() => { setAddVisa(null); setCtx(null) }}
               footer={<><button className="btn" onClick={() => { setAddVisa(null); setCtx(null) }}>取消</button>
                 <button className="btn primary" onClick={submitAdd}>提交追加（送项目经理审核）</button></>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            <Badge status="DRAFT" label={CHANGE_TYPE[addVisa.changeType]} tone="blue" />{' '}
            {addVisa.building} {addVisa.area} · {addVisa.process} · {addVisa.drawingNo}
            <div style={{ marginTop: 6 }}>{addVisa.description}</div>
          </div>

          {/* 平台关联：原预算 / 已领材料 / 现场库存 */}
          <div className="grid3" style={{ marginBottom: 10 }}>
            {ctx.budgets.map((b) => (
              <div key={b.id} className="kpi-note">
                原预算：<b>{b.materialName}</b> {fmt(b.budgetQty)} {b.unit}
                <div className="muted" style={{ fontSize: 12 }}>预算单价 {fmt(b.budgetPrice)} 元</div>
              </div>
            ))}
            {ctx.issued.map((i) => (
              <div key={i.materialId} className="kpi-note">
                已领材料：<b>{i.materialName}</b> {fmt(i.issuedQty)} {i.unit}
                <div className="muted" style={{ fontSize: 12 }}>合同内已发金额 {fmt(i.issuedAmount)} 元</div>
              </div>
            ))}
            <div className="kpi-note">
              现场库存（{meta.materials.find((x) => x.id === addVisa.materialId)?.name || '—'}）
              ：<b>{fmt(ctx.onHandQty)}</b>
              <div className="muted" style={{ fontSize: 12 }}>{ctx.batches.length} 个可用批次</div>
            </div>
          </div>

          {ctx.additions.length > 0 && (
            <div className="kpi-note" style={{ marginBottom: 10 }}>
              已有追加领料：{ctx.additions.map((a) =>
                `${a.code}(${a.status}/${COST_BEARING_SHORT[a.costBearing] || a.costBearing})`).join('，')}
            </div>
          )}

          <div className="grid3">
            <Field label="追加班组 *">
              <select value={af.crewId} disabled={user.role === 'ROLE_FOREMAN'}
                      onChange={(e) => setAf({ ...af, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="成本归属 *">
              <select value={af.costBearing} onChange={(e) => setAf({ ...af, costBearing: e.target.value })}>
                <option value="VISA_CLAIM">{COST_BEARING_SHORT.VISA_CLAIM}</option>
                <option value="REWORK">{COST_BEARING_SHORT.REWORK}</option>
              </select>
            </Field>
            <Field label="追加人工（工日 × 单价）">
              <div style={{ display: 'flex', gap: 6 }}>
                <input type="number" placeholder="工日" value={af.laborQty}
                  onChange={(e) => setAf({ ...af, laborQty: e.target.value })} />
                <input type="number" placeholder="单价" value={af.laborPrice}
                  onChange={(e) => setAf({ ...af, laborPrice: e.target.value })} />
              </div>
            </Field>
          </div>

          <div style={{ margin: '10px 0 6px', fontWeight: 600 }}>新增材料需求</div>
          <table className="tbl">
            <thead><tr><th>材料</th><th style={{ width: 160 }}>追加数量</th><th style={{ width: 70 }}></th></tr></thead>
            <tbody>
            {af.items.map((it, idx) => (
              <tr key={idx}>
                <td>
                  <select value={it.materialId} onChange={(e) => {
                    const items = af.items.slice()
                    items[idx] = { ...items[idx], materialId: e.target.value }
                    setAf({ ...af, items })
                  }}>
                    <option value="">请选择材料</option>
                    {meta.materials.map((m) => <option key={m.id} value={m.id}>[{m.category}] {m.name}（{m.unit}）</option>)}
                  </select>
                </td>
                <td><input type="number" value={it.applyQty} onChange={(e) => {
                  const items = af.items.slice()
                  items[idx] = { ...items[idx], applyQty: e.target.value }
                  setAf({ ...af, items })
                }} /></td>
                <td>{af.items.length > 1 && (
                  <button className="btn sm danger" onClick={() => setAf({ ...af, items: af.items.filter((_, i) => i !== idx) })}>删</button>
                )}</td>
              </tr>
            ))}
            </tbody>
          </table>
          <button className="btn" style={{ marginTop: 10 }}
                  onClick={() => setAf({ ...af, items: [...af.items, blankLine()] })}>＋ 增加材料</button>

          <Field label="追加说明"><input value={af.remark} onChange={(e) => setAf({ ...af, remark: e.target.value })} /></Field>
          <div className="kpi-note" style={{ marginTop: 8 }}>
            追加人工费预估：<b>{fmt(laborEstimate)}</b> 元；
            提交后由项目经理审核，审核通过仓管发料，新增材料与人工将按"
            <b>{COST_BEARING_SHORT[af.costBearing]}</b>"口径进入月底核算。
          </div>
        </Modal>
      )}
    </Card>
  )
}
