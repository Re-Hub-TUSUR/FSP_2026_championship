import {create} from 'zustand';
type S={balance:number; theme:'RED'|'GREEN'; setBalance:(n:number)=>void; setTheme:(t:'RED'|'GREEN')=>void};
export const useStore=create<S>(set=>({balance:0,theme:'RED',setBalance:balance=>set({balance}),setTheme:theme=>set({theme})}));
