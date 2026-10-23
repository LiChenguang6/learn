const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname,'../app/src/main/assets/claim-page.js'), 'utf8');
function run({text='', pathname='/creatorlink/test/mush-fans',host='accounts.klei.com',button=false,disabled=false,hidden=false,password=false,loginLink=false,form=false,click=false,expectedPath=pathname}={}) {
  let clicks=0;
  const element = (innerText, extra={}) => ({innerText,tagName:'BUTTON',disabled:false,getAttribute:()=>'',getBoundingClientRect:()=>({width:100,height:30}),...extra});
  const b=element('Claim',{disabled,hidden,click:()=>clicks++});
  const context={location:{hostname:host,pathname},window:{__kleiDoClick:click,__kleiExpectedPath:expectedPath,getComputedStyle:e=>({display:e.hidden?'none':'block',visibility:'visible'})},document:{body:{innerText:text},querySelectorAll:selector=>selector==='input[type=password]'?(password?[element('')]:[]):selector==='form'?(form?[element('',{getAttribute:()=>'/login'})]:[]):[...(button?[b]:[]),...(loginLink?[element('Sign in')]:[])]}};
  return {state:vm.runInNewContext(script,context),clicks};
}
for (const [name,options,state] of [
 ['normal button',{button:true},'READY'],
 ['login words in footer',{button:true,text:'Please log in for other features'},'READY'],
 ['account redirect',{pathname:'/account',text:'My account'},'INTERMEDIATE'],
 ['external redirect',{host:'steamcommunity.com'},'INTERMEDIATE'],
 ['queue page',{text:'Please wait 排队',button:true},'BUSY'],
 ['challenge',{text:'Checking your browser. Please log in'},'BUSY'],
 ['server overload',{text:'Service unavailable'},'BUSY'],
 ['password form',{password:true},'LOGIN'],
 ['non-gift login form',{pathname:'/login',form:true},'LOGIN'],
 ['explicit login prompt',{text:'Please log in to claim this reward',loginLink:true},'LOGIN'],
 ['footer alone',{text:'Please log in'},'WAIT'],
 ['someone else Chinese',{text:'该礼物已被其他人领取'},'TAKEN'],
 ['someone else English',{text:'This gift was claimed by someone else'},'TAKEN'],
 ['already taken English',{text:'Someone else has already claimed this gift'},'TAKEN'],
 ['own successful claim',{text:'You have successfully claimed this gift'},'ALREADY'],
 ['ambiguous already claimed',{text:'Already claimed'},'UNAVAILABLE'],
 ['sold out',{text:'Sold out'},'UNAVAILABLE'],
 ['disabled button',{button:true,disabled:true},'WAIT'],
 ['hidden button',{button:true,hidden:true},'WAIT'],
 ['empty page',{},'WAIT']
]) test(name,()=>assert.equal(run(options).state,state));
test('inspect never clicks',()=>assert.deepEqual(run({button:true}),{state:'READY',clicks:0}));
test('click dispatches once',()=>assert.deepEqual(run({button:true,click:true}),{state:'CLICKED',clicks:1}));
test('taken page cannot click',()=>assert.deepEqual(run({button:true,click:true,text:'已被其他人领取'}),{state:'TAKEN',clicks:0}));
test('external page cannot click',()=>assert.deepEqual(run({button:true,click:true,host:'other.example'}),{state:'INTERMEDIATE',clicks:0}));
test('busy page cannot click',()=>assert.deepEqual(run({button:true,click:true,text:'Just a moment'}),{state:'BUSY',clicks:0}));

test("stale gift page cannot click",()=>assert.deepEqual(run({button:true,click:true,expectedPath:"/creatorlink/other/mush-fans"}),{state:"INTERMEDIATE",clicks:0}));

test("footer login link without claim prompt is not expiry",()=>assert.equal(run({text:"Please log in for other features",loginLink:true}).state,"WAIT"));
