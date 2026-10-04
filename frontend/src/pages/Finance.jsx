import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { Card, Stats, Badge, fmt, fmtTime } from '../ui.jsx'

const CHANGE_LABEL = { DESIGN: '设计变更', CONSTRUCTION: '施工变更', OWNER: '业主要求' }
const BEARING_LABEL = { VISA: '可向甲方签证', CREW: '施工返工·班组承担', COMPANY: '项目承担' }
const BEARING_TONE = { VISA: 'blue', CREW: 'red', COMPANY: 'gray' }

export default function Finance() {
  const [data, setData] = useState(null)
  useEffect(() => { api.get('/api/analytics/finance').then(setData).catch(() => {}) }, [])

  return (
    <div>
      <Stats items={[
        { label: '合同内材料成本', value: fmt(data?.contractTotalCost), tone: 'brand' },
        { label: '签证成本（材料+人工）', value: fmt(data?.visaTotalCost), tone: 'blue' },
        { label: '施工返工成本（班组承担）', value: fmt(data?.reworkTotalCost), tone: 'red' },
        { label: '班组扣款合计', value: fmt(data?.crewTotalDeduction), tone: 'red' },
        { label: '对甲方签证索赔合计', value: fmt(data?.visaTotalClaim), tone: 'blue' }
      ]} />

      <Card title="成本口径拆分（月底核算）"
            hint="追加领料通过后新增材料与变更编号绑定，财务可直接区分合同内成本与签证成本；施工返工计入责任班组" flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>成本类别</th><th className="num">材料费</th><th className="num">追加人工费</th>
              <th className="num">合计</th><th>去向</th>
            </tr></thead>
            <tbody>
              <tr>
                <td><b>合同内成本</b></td>
                <td className="num">{fmt(data?.contractTotalCost)}</td>
                <td className="num">—</td>
                <td className="num"><b>{fmt(data?.contractTotalCost)}</b></td>
                <td><Badge status="PASSED" label="进入工程合同成本" /></td>
              </tr>
              <tr>
                <td><b>可向甲方签证成本</b></td>
                <td className="num">{fmt(data?.visaMaterialTotal)}</td>
                <td className="num">{fmt(data?.visaLaborTotal)}</td>
                <td className="num" style={{ color: 'var(--blue)' }}><b>{fmt(data?.visaTotalCost)}</b></td>
                <td><Badge status="CLAIMED" label="进入变更索赔清单" /></td>
              </tr>
              <tr>
                <td><b>施工返工成本</b></td>
                <td className="num">{fmt(data?.reworkMaterialTotal)}</td>
                <td className="num">{fmt(data?.reworkLaborTotal)}</td>
                <td className="num pos"><b>{fmt(data?.reworkTotalCost)}</b></td>
                <td><Badge status="FAILED" label="计入责任班组扣款" /></td>
              </tr>
              <tr>
                <td><b>项目自身承担</b></td>
                <td className="num" colSpan={2}>{fmt(data?.projectBearTotalCost)}</td>
                <td className="num">{fmt(data?.projectBearTotalCost)}</td>
                <td><span className="muted">不索赔、不扣班组</span></td>
              </tr>            </tbody>
          </table>
        </div>
      </Card>

      <Card title="变更追加领料台账（材料与变更编号绑定）" flush
            hint="防水高度调整等变更引发的追加卷材/人工，按 PM 审核的成本承担方分流">
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>追加单号</th><th>变更签证</th><th>追加领料单</th><th>楼栋/工序</th><th>班组</th>
              <th>事由</th><th>成本承担</th><th className="num">材料费</th><th className="num">人工费</th>
              <th className="num">合计</th><th>状态</th>
            </tr></thead>
            <tbody>
            {(data?.supplements || []).map((s) => (
              <tr key={s.code}>
                <td className="code-link">{s.code}</td>
                <td><b>{s.visaCode}</b></td>
                <td>{s.requisitionCode || '—'}</td>
                <td>{s.building} {s.area}<div className="muted" style={{ fontSize: 12 }}>{s.process}</div></td>
                <td>{s.crewName}</td>
                <td className="desc-cell" style={{ maxWidth: 220 }}>{s.reason}</td>
                <td><Badge status="DRAFT" label={BEARING_LABEL[s.costBearing]} tone={BEARING_TONE[s.costBearing]} /></td>
                <td className="num">{fmt(s.materialAmount)}</td>
                <td className="num">{fmt(s.laborCost)}</td>
                <td className="num"><b>{fmt(s.totalAmount)}</b></td>
                <td><Badge status={s.status} /></td>
              </tr>
            ))}
            {(!data?.supplements || data.supplements.length === 0) && (
              <tr><td colSpan={11}><div className="empty">暂无变更追加领料</div></td></tr>
            )}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="供应商结算台账" hint="按入库批次金额汇总；质检不合格批次金额从应付款中扣减" flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>供应商</th><th className="num">入库金额</th><th className="num">质检不合格金额</th>
              <th className="num">建议应付金额</th><th>结算提示</th>
            </tr></thead>
            <tbody>
            {(data?.suppliers || []).map((s) => (
              <tr key={s.supplierId}>
                <td><b>{s.supplierName}</b></td>
                <td className="num">{fmt(s.inboundAmount)}</td>
                <td className="num pos">{fmt(s.qcRejectedAmount)}</td>
                <td className="num"><b>{fmt(s.payableAmount)}</b></td>
                <td>{s.qcRejectedAmount > 0
                  ? <Badge status="FAILED" label="有不合格批次，结算扣款" />
                  : <Badge status="PASSED" label="质检无异常" />}</td>
              </tr>
            ))}
            </tbody>
          </table>
        </div>
        <div className="kpi-note" style={{ marginTop: 10 }}>
          供应商质量损耗索赔：<b className="pos">{fmt(data?.supplierQualityClaim)}</b> 元（不合格补货批次清退出场，单列向供应商索赔）
        </div>
      </Card>

      <div className="grid2">
        <Card title="班组扣款台账（取各班组最新月份核算单）" flush>
          <div className="table-scroll">
            <table className="tbl">
              <thead><tr>
                <th>班组</th><th>月份</th><th className="num">合同内成本</th>
                <th className="num">签证成本</th><th className="num">返工成本</th>
                <th className="num">应扣金额</th><th>状态</th>
              </tr></thead>
              <tbody>
              {(data?.crews || []).map((c) => (
                <tr key={c.crewId}>
                  <td><b>{c.crewName}</b></td>
                  <td>{c.period}</td>
                  <td className="num">{fmt(c.contractCostAmount)}</td>
                  <td className="num" style={{ color: 'var(--blue)' }}>
                    {fmt((c.visaMaterialAmount || 0) + (c.visaLaborAmount || 0))}</td>
                  <td className="num pos">
                    {fmt((c.reworkMaterialAmount || 0) + (c.reworkLaborAmount || 0))}</td>
                  <td className="num pos"><b>{fmt(c.deductionAmount)}</b></td>
                  <td><Badge status={c.status} /></td>
                </tr>
              ))}
              {(!data?.crews || data.crews.length === 0) && (
                <tr><td colSpan={7}><div className="empty">尚未生成班组核算单</div></td></tr>
              )}
              </tbody>
            </table>
          </div>
        </Card>

        <Card title="变更签证索赔台账" flush
              hint="绑定追加单的签证按追加实发材料+人工列示；施工返工类不计对甲方索赔">
          <div className="table-scroll">
            <table className="tbl">
              <thead><tr>
                <th>签证号</th><th>楼栋/工序</th><th>类型</th><th>追加领料</th>
                <th className="num">材料费</th><th className="num">人工费</th>
                <th className="num">索赔金额</th><th>状态</th>
              </tr></thead>
              <tbody>
              {(data?.visas || []).map((v) => (
                <tr key={v.code}>
                  <td className="code-link">{v.code}</td>
                  <td>{v.building}<div className="muted" style={{ fontSize: 12 }}>{v.process}</div></td>
                  <td>{CHANGE_LABEL[v.changeType]}</td>
                  <td style={{ fontSize: 12 }}>
                    {v.supplementRequisition
                      ? <><b>{v.supplementRequisition}</b>
                          {v.costBearing && <div><Badge status="DRAFT"
                            label={BEARING_LABEL[v.costBearing]} tone={BEARING_TONE[v.costBearing]} /></div>}</>
                      : <span className="muted">登记口径</span>}
                  </td>
                  <td className="num">{v.materialAmount == null ? '—' : fmt(v.materialAmount)}</td>
                  <td className="num">{v.laborAmount == null ? '—' : fmt(v.laborAmount)}</td>
                  <td className="num"><b>{fmt(v.claimAmount)}</b></td>
                  <td><Badge status={v.status} /></td>
                </tr>
              ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </div>
  )
}
