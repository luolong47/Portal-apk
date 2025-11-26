package moe.fuqiuluo.portal.ui.viewmodel

import androidx.lifecycle.ViewModel

/**
 * 主页视图模型
 * 
 * 管理主页UI状态，如浮动按钮的展开/收起状态
 */
class HomeViewModel : ViewModel() {
    /* Fab */
    /**
     * 浮动按钮是否已展开
     */
    var mFabOpened = false
}