import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { Card, Stats, Badge, fmt, fmtTime } from '../ui.jsx'

const CHANGE_LABEL = { DESIGN: '设计变更', CONSTRUCTION: '施工变更', OWNER: '业主要求' }

export default function Finance() {
  const [data, setData] = useState(null)
  useEffect(() => { api.get('/api/analytics/finance').then(setData).catch(() => {}) }, [])

  return (
    <div>
      <Stats items={[
        { label: '供应商入库总额', value: fmt(data?.supplierTotalInbound), tone: 'blue' },
        { label: '供应商应付（扣不合格）', value: fmt(data?.supplierTotalPayable), tone: 'brand' },
        { label: '供应商质量索赔', value: fmt(data?.supplierQualityClaim), tone: 'red' },
        { label: '合同内材料成本', value: fmt(data?.contractCostTotal), tone: 'green' },
        { label: '变更签证追加成本', value: fmt(data?.visaCostTotal), tone: 'blue' },
        { label: '施工返工成本（计班组）', value: fmt(data?.reworkCostTotal), tone: 'red' },
        { label: '班组材料扣款合计', value: fmt(data?.crewTotalDeduction), tone: 'red' },
        { label: '变更签证索赔合计', value: fmt(data?.visaTotalClaim), tone: 'blue' }
      ]} />

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
      </Card>

      <div className="grid2">
        <Card title="班组扣款台账（取各班组最新月份核算单）" flush>
          <div className="table-scroll">
            <table className="tbl">
              <thead><tr>
                <th>班组</th><th>月份</th><th className="num">合同内成本</th>
                <th className="num">签证追加成本</th><th className="num">返工成本</th>
                <th className="num">责任损耗</th><th className="num">应扣金额</th><th>状态</th>
              </tr></thead>
              <tbody>
              {(data?.crews || []).map((c) => (
                <tr key={c.crewId}>
                  <td><b>{c.crewName}</b></td>
                  <td>{c.period}</td>
                  <td className="num">{fmt(c.contractCostAmount)}</td>
                  <td className="num neg">{fmt(c.visaCostAmount)}</td>
                  <td className="num pos">{fmt(c.reworkCostAmount)}</td>
                  <td className="num pos">{fmt(c.crewWasteAmount)}</td>
                  <td className="num pos"><b>{fmt(c.deductionAmount)}</b></td>
                  <td><Badge status={c.status} /></td>
                </tr>
              ))}
              {(!data?.crews || data.crews.length === 0) && (
                <tr><td colSpan={8}><div className="empty">尚未生成班组核算单</div></td></tr>
              )}
              </tbody>
            </table>
          </div>
        </Card>

        <Card title="变更索赔台账（追加领料发料后绑定变更编号）" flush>
          <div className="table-scroll">
            <table className="tbl">
              <thead><tr>
                <th>签证号</th><th>楼栋/工序</th><th>类型</th>
                <th className="num">绑定材料</th><th className="num">绑定人工</th>
                <th className="num">签证成本</th><th className="num">返工成本</th>
                <th>追加领料单</th><th>状态</th>
              </tr></thead>
              <tbody>
              {(data?.visas || []).map((v) => (
                <tr key={v.code}>
                  <td className="code-link">{v.code}</td>
                  <td>{v.building}<div className="muted" style={{ fontSize: 12 }}>{v.process}</div></td>
                  <td>{CHANGE_LABEL[v.changeType]}</td>
                  <td className="num">{fmt(v.boundMaterialCost)}</td>
                  <td className="num">{fmt(v.boundLaborCost)}</td>
                  <td className="num neg"><b>{fmt(v.visaClaimCost)}</b></td>
                  <td className="num pos">{fmt(v.reworkCost)}</td>
                  <td style={{ fontSize: 12 }}>{(v.requisitionCodes || []).join('，') || '—'}</td>
                  <td><Badge status={v.status} />
                    {v.bound && <div className="muted" style={{ fontSize: 12 }}>已绑定发料</div>}
                  </td>
                </tr>
              ))}
              </tbody>
            </table>
          </div>
          <div className="kpi-note" style={{ margin: 10 }}>
            月底核算口径：合同内成本 = 普通领料净消耗；签证追加成本（可向甲方签证）单列索赔，不扣班组；
            施工返工追加成本（材料+人工）计入责任班组扣款。
            当前已发料追加：签证成本 <b>{fmt(data?.visaBoundCostTotal)}</b> 元，
            返工成本 <b className="pos">{fmt(data?.reworkBoundCostTotal)}</b> 元。
          </div>
        </Card>
      </div>
    </div>
  )
}
