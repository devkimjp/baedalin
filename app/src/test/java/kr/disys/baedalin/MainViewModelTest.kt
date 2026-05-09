package kr.disys.baedalin

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import kr.disys.baedalin.domain.usecase.GetPresetsUseCase
import kr.disys.baedalin.domain.usecase.SavePresetUseCase
import kr.disys.baedalin.model.ClickType
import kr.disys.baedalin.model.DeliveryFunction
import kr.disys.baedalin.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @Mock
    private lateinit var getPresetsUseCase: GetPresetsUseCase
    
    @Mock
    private lateinit var savePresetUseCase: SavePresetUseCase
    
    @Mock
    private lateinit var context: Context
    
    @Mock
    private lateinit var prefs: SharedPreferences
    
    @Mock
    private lateinit var prefsEditor: SharedPreferences.Editor
    
    @Mock
    private lateinit var inputManager: InputManager

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        
        // Mock SharedPreferences
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(prefsEditor)
        `when`(prefsEditor.putBoolean(anyString(), anyBoolean())).thenReturn(prefsEditor)
        `when`(prefsEditor.putString(anyString(), anyString())).thenReturn(prefsEditor)
        `when`(prefsEditor.putInt(anyString(), anyInt())).thenReturn(prefsEditor)
        
        // Mock UseCases
        `when`(getPresetsUseCase.invoke()).thenReturn(flowOf(emptyList()))
        
        // Mock InputManager & System Services
        `when`(context.getSystemService(Context.INPUT_SERVICE)).thenReturn(inputManager)
        
        // Android SDK 의존성이 있는 부분들을 회피하기 위해
        // ViewModel을 수동으로 초기화하는 대신 로직 단위 검증에 집중
        viewModel = MainViewModel(getPresetsUseCase, savePresetUseCase, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testToggleService() = runTest {
        // Given
        val initialState = viewModel.uiState.value.isMappingEnabled
        
        // When
        viewModel.toggleService()
        
        // Then
        assertEquals(!initialState, viewModel.uiState.value.isMappingEnabled)
        verify(prefsEditor).putBoolean(eq("is_mapping_enabled"), eq(!initialState))
    }

    @Test
    fun testOpenMappingWizard() = runTest {
        // When
        viewModel.openMappingWizard()
        
        // Then
        assertTrue(viewModel.uiState.value.isMappingWizardActive)
    }

    @Test
    fun testCloseMappingWizard() = runTest {
        // Given
        viewModel.openMappingWizard()
        
        // When
        viewModel.closeMappingWizard()
        
        // Then
        assertFalse(viewModel.uiState.value.isMappingWizardActive)
        assertNull(viewModel.uiState.value.pendingKeyCode)
    }

    @Test
    fun testExecuteSaveMapping() = runTest {
        // When
        viewModel.executeSaveMapping(DeliveryFunction.ACCEPT, ClickType.SINGLE, 100)
        
        // Then
        verify(prefsEditor).putInt(anyString(), eq(100))
        assertFalse(viewModel.uiState.value.isMappingWizardActive)
    }
}
