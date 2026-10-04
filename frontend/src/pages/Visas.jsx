import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta, CHANGE_TYPE, COST_BEARING, COST_BEARING_TONE } from '../ui.jsx'

export default function Visas() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const canCreate = ['ROLE_PM', 'ROLE_BUDGET', 'ROLE_KEEPER'].includes(user.role)
  const canConfirm = ['ROLE_PM', 'ROLE_BUDGET'].includes(user.role)
  const canClaim = user.role === 'ROLE_FINANCE'

  const [rows, setRows] = useState([])
  const [supps, setSupps] = useState([])
  const [show, setShow] = useState(false)
  const [f, setF] = useState({
    building: '1#楼', area: '', process: '', drawingNo: '', materialId: '', crewId: '',
    changeType: 'DESIGN', description: '', qtyChange: '', unitPrice: '', claimAmount: ''
  })

  const load = () => {
    api.get('/api/visas').then(setRows)
    api.get('/api/supplements').then(setSupps).catch(() => {})
  }
  useEffect(() => { load() }, [])

  const suppById = (id) => supps.find((s) => s.id === id)

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

  const total = rows.filter((v) => v.status !== 'DRAFT').reduce((s, v) => s + (v.claimAmount || 0), 0)

  return (
    <Card title={`变更签证（已确认索赔 ${fmt(total)} 元）`}
          hint="施工/设计变更引起的材料增减与费用，串联退库、损耗与财务变更索赔"
          actions={canCreate && <button className="btn primary" onClick={() => setShow(true)}>＋ 登记变更签证</button>}
          flush>
      <div className="table-scroll">
        <table className="tbl">
          <thead><tr>
            <th>签证号</th><th>楼栋/工序</th><th>变更类型</th><th>涉及材料/班组</th>
            <th className="num">材料量变化</th><th className="num">索赔金额</th><th>状态</th>
            <th>内容</th><th>追加领料绑定</th><th>登记/确认时间</th><th>操作</th>
          </tr></thead>
          <tbody>
          {rows.map((v) => {
            const m = meta.materials.find((x) => x.id === v.materialId)
            const c = meta.crews.find((x) => x.id === v.crewId)
            const sp = suppById(v.supplementId)
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
                <td style={{ fontSize: 12 }}>
                  {sp ? (
                    <>
                      <div><b>{sp.code}</b></div>
                      <div className="muted">{sp.requisitionCode || '待生成领料单'}</div>
                      <div style={{ marginTop: 2 }}>
                        <Badge status="DRAFT" label={COST_BEARING[sp.costBearing]}
                               tone={COST_BEARING_TONE[sp.costBearing]} />
                      </div>
                    </>
                  ) : <span className="muted">未绑定追加领料</span>}
                </td>
                <td style={{ fontSize: 12 }}>{fmtTime(v.createdTime)}<div className="muted">{fmtTime(v.confirmTime)}</div></td>
                <td>
                  {v.status === 'DRAFT' && canConfirm && <button className="btn sm primary" onClick={() => confirm(v)}>确认</button>}
                  {v.status === 'CONFIRMED' && canClaim && <button className="btn sm" onClick={() => claim(v)}>标记索赔</button>}
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
            placeholder="例：地下室后浇带设计变更，附加层范围扩大，增加卷材并产生拆除损耗" /></Field>
        </Modal>
      )}
    </Card>
  )
}
