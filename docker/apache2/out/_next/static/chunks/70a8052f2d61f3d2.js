(globalThis.TURBOPACK||(globalThis.TURBOPACK=[])).push(["object"==typeof document?document.currentScript:void 0,18566,(e,t,a)=>{t.exports=e.r(76562)},89700,e=>{"use strict";var t=e.i(43476);let a=`
  .pg-btn {
    min-width: 36px; height: 36px; padding: 0 10px;
    border: 1px solid #d0e8f0; border-radius: 6px;
    background: #fff; color: #2c3e50;
    font-size: 14px; font-family: inherit;
    cursor: pointer; transition: all .15s;
  }
  .pg-btn:hover:not(:disabled) {
    background: #e8f4f8; border-color: #0fa3b1; color: #1a6b8a;
  }
  .pg-btn.active {
    background: #1a6b8a; color: #fff;
    border-color: #1a6b8a; font-weight: 700;
    box-shadow: 0 2px 8px #1a6b8a55;
    transform: translateY(-1px);
  }
  .pg-btn:disabled { opacity: 0.3; cursor: not-allowed; }
  .pg-btn.ellipsis {
    border: none; background: transparent;
    color: #7f8c8d; cursor: default; letter-spacing: 1px;
  }
  .pg-btn.ellipsis:hover { background: transparent; color: #7f8c8d; }
`,r=({label:e,target:a,disabled:r=!1,className:n="",onPageChange:s})=>(0,t.jsx)("button",{className:`pg-btn ${n}`,onClick:()=>!r&&s(a),disabled:r,children:e});function n({currentPage:e,totalCount:n,postNum:s,onPageChange:l,pageGroupSize:i=10}){if(!n||!s)return null;let o=Math.ceil(n/s),d=(Math.ceil(e/i)-1)*i+1,c=Math.min(d+i-1,o),g=Array.from({length:c-d+1},(e,t)=>d+t);return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)("style",{children:a}),(0,t.jsxs)("div",{style:{display:"flex",justifyContent:"center",alignItems:"center",gap:"4px"},children:[(0,t.jsx)(r,{label:"«",target:1,disabled:1===e,onPageChange:l}),(0,t.jsx)(r,{label:"‹",target:e-1,disabled:1===e,onPageChange:l}),d>1&&(0,t.jsx)(r,{label:"···",target:d-1,className:"ellipsis",onPageChange:l}),g.map(a=>(0,t.jsx)(r,{label:String(a),target:a,className:a===e?"active":"",onPageChange:l},a)),c<o&&(0,t.jsx)(r,{label:"···",target:c+1,className:"ellipsis",onPageChange:l}),(0,t.jsx)(r,{label:"›",target:e+1,disabled:e===o,onPageChange:l}),(0,t.jsx)(r,{label:"»",target:o,disabled:e===o,onPageChange:l})]})]})}e.s(["default",()=>n])},76144,e=>{"use strict";var t=e.i(43476),a=e.i(71645),r=e.i(18566),n=e.i(89700);function s(){let e=(0,r.useSearchParams)(),s=(0,a.useRef)(e.get("addrSearch")??""),[l,i]=(0,a.useState)([]),[o,d]=(0,a.useState)(1),[c,g]=(0,a.useState)(0),[h,p]=(0,a.useState)(0);return(0,a.useEffect)(()=>{(async()=>{let e=`page=${o}&addrSearch=${encodeURIComponent(s.current)}`;try{let t=await fetch(`/api/member/searchAddress?${e}`,{method:"GET",credentials:"include"}),a=await t.json();i(a.content||[]),g(a.totalElements),p(a.size)}catch(e){console.error("주소 검색 실패:",e)}})()},[o]),(0,t.jsxs)("div",{className:"main",children:[(0,t.jsx)("h1",{children:"주소 검색"}),(0,t.jsxs)("table",{className:"InfoTable",children:[(0,t.jsx)("thead",{children:(0,t.jsxs)("tr",{children:[(0,t.jsx)("th",{children:"우편번호"}),(0,t.jsx)("th",{children:"주소"}),(0,t.jsx)("th",{children:"선택"})]})}),(0,t.jsx)("tbody",{children:l.map((e,a)=>(0,t.jsxs)("tr",{children:[(0,t.jsx)("td",{children:e.zipcode}),(0,t.jsxs)("td",{style:{textAlign:"left"},children:[e.province,e.road,e.building,(0,t.jsx)("br",{}),e.oldaddr]}),(0,t.jsx)("td",{children:(0,t.jsx)("input",{type:"button",value:"선택",onClick:()=>{var t,a,r,n;return t=e.zipcode,a=e.province,r=e.road,n=e.building,void(window.opener.postMessage({zipcode:t,addr1:a+r+n},"*"),window.close())}})})]},a))})]}),(0,t.jsx)("br",{}),(0,t.jsx)(n.default,{currentPage:o,totalCount:c,postNum:h,pageGroupSize:5,onPageChange:e=>{e<1||d(e)}})]})}function l(){return(0,t.jsx)(a.Suspense,{fallback:(0,t.jsx)("div",{style:{textAlign:"center",marginTop:"50px"},children:"로딩 중..."}),children:(0,t.jsx)(s,{})})}e.s(["default",()=>l])}]);