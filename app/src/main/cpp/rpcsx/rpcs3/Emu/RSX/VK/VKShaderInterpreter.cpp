#include "stdafx.h"

#include "VKShaderInterpreter.h"
#include "VKCommonPipelineLayout.h"
#include "VKVertexProgram.h"
#include "VKFragmentProgram.h"
#include "Emu/System.h"
#include "../Program/GLSLCommon.h"
#include "../Program/ShaderInterpreter.h"
#include "../rsx_methods.h"
#include "../Overlays/Shaders/shader_loading_dialog.h"
#include "VKHelpers.h"
#include "VKRenderPass.h"
#include <chrono>
#include <thread>

namespace vk
{
	namespace
	{
		std::vector<u64> get_interpreter_preload_variants()
		{
			using namespace program_common::interpreter;

			const std::array<u64, 11> fs_bases =
				{
					0,
					COMPILER_OPT_ENABLE_TEXTURES,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_F32_EXPORT,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_FLOW_CTRL,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_PACKING,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_KIL,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_DEPTH_EXPORT,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_FLOW_CTRL | COMPILER_OPT_ENABLE_PACKING,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_F32_EXPORT | COMPILER_OPT_ENABLE_FLOW_CTRL | COMPILER_OPT_ENABLE_PACKING,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_F32_EXPORT | COMPILER_OPT_ENABLE_FLOW_CTRL | COMPILER_OPT_ENABLE_PACKING | COMPILER_OPT_ENABLE_KIL,
					COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_F32_EXPORT | COMPILER_OPT_ENABLE_FLOW_CTRL | COMPILER_OPT_ENABLE_PACKING | COMPILER_OPT_ENABLE_DEPTH_EXPORT,
				};

			const std::array<u64, 6> alpha_tests =
				{
					COMPILER_OPT_ENABLE_ALPHA_TEST_GE,
					COMPILER_OPT_ENABLE_ALPHA_TEST_G,
					COMPILER_OPT_ENABLE_ALPHA_TEST_LE,
					COMPILER_OPT_ENABLE_ALPHA_TEST_L,
					COMPILER_OPT_ENABLE_ALPHA_TEST_EQ,
					COMPILER_OPT_ENABLE_ALPHA_TEST_NE,
				};

			std::vector<u64> result;
			result.reserve((fs_bases.size() + alpha_tests.size() * 2) * 2);

			const auto add_vs_variants = [&result](u64 fs_opts)
			{
				result.push_back(fs_opts);
				result.push_back(fs_opts | COMPILER_OPT_ENABLE_INSTANCING);
			};

			for (const u64 fs_opts : fs_bases)
			{
				add_vs_variants(fs_opts);
			}

			for (const u64 alpha_test : alpha_tests)
			{
				add_vs_variants(COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_F32_EXPORT | alpha_test);
				add_vs_variants(COMPILER_OPT_ENABLE_TEXTURES | COMPILER_OPT_ENABLE_F32_EXPORT | COMPILER_OPT_ENABLE_FLOW_CTRL | COMPILER_OPT_ENABLE_PACKING | alpha_test);
			}

			std::sort(result.begin(), result.end());
			result.erase(std::unique(result.begin(), result.end()), result.end());
			return result;
		}

		std::vector<vk::pipeline_props> get_interpreter_preload_pipelines()
		{
			std::vector<vk::pipeline_props> pipe_properties;

			vk::pipeline_props base_props{};
			base_props.state.set_attachment_count(1);
			base_props.state.enable_cull_face(VK_CULL_MODE_BACK_BIT);
			base_props.state.set_primitive_type(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
			base_props.state.set_color_mask(0, true, true, true, true);
			base_props.renderpass_key = vk::get_renderpass_key(VK_FORMAT_B8G8R8A8_UNORM);
			pipe_properties.push_back(base_props);

			base_props.state.enable_blend(0,
				VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
				VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
				VK_BLEND_OP_ADD, VK_BLEND_OP_ADD);
			pipe_properties.push_back(base_props);

			return pipe_properties;
		}
	}

	glsl::shader* shader_interpreter::build_vs(u64 compiler_options)
	{
		::glsl::shader_properties properties{};
		properties.domain = ::glsl::program_domain::glsl_vertex_program;
		properties.require_lit_emulation = true;

		// TODO: Extend decompiler thread
		// TODO: Rename decompiler thread, it no longer spawns a thread
		RSXVertexProgram null_prog;
		std::string shader_str;
		ParamArray arr;
		VKVertexProgram vk_prog;

		null_prog.ctrl = (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_INSTANCING) ? RSX_SHADER_CONTROL_INSTANCED_CONSTANTS : 0;
		VKVertexDecompilerThread comp(null_prog, shader_str, arr, vk_prog);

		// Initialize compiler properties
		comp.properties.has_indexed_constants = true;

		ParamType uniforms = {PF_PARAM_UNIFORM, "vec4"};
		uniforms.items.emplace_back("vc[468]", -1);

		std::stringstream builder;
		comp.insertHeader(builder);
		comp.insertConstants(builder, {uniforms});
		comp.insertInputs(builder, {});

		// Insert vp stream input
		builder << "\n"
				   "layout(std140, set=0, binding="
				<< m_vertex_instruction_start << ") readonly restrict buffer VertexInstructionBlock\n"
												 "{\n"
												 "	uint base_address;\n"
												 "	uint entry;\n"
												 "	uint output_mask;\n"
												 "	uint control;\n"
												 "	uvec4 vp_instructions[];\n"
												 "};\n\n";

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_INSTANCING)
		{
			builder << "#define _ENABLE_INSTANCED_CONSTANTS\n";
		}

		if (compiler_options)
		{
			builder << "\n";
		}

		::glsl::insert_glsl_legacy_function(builder, properties);
		::glsl::insert_vertex_input_fetch(builder, ::glsl::glsl_rules::glsl_rules_vulkan);

		builder << program_common::interpreter::get_vertex_interpreter();
		const std::string s = builder.str();

		auto vs = std::make_unique<glsl::shader>();
		vs->create(::glsl::program_domain::glsl_vertex_program, s);
		vs->compile();

		if (m_vs_inputs.empty())
		{
			// Prepare input table
			const auto& binding_table = vk::get_current_renderer()->get_pipeline_binding_table();
			vk::glsl::program_input in;

			in.location = binding_table.vertex_params_bind_slot;
			in.domain = ::glsl::glsl_vertex_program;
			in.name = "VertexContextBuffer";
			in.type = vk::glsl::input_type_uniform_buffer;
			m_vs_inputs.push_back(in);

			in.location = binding_table.vertex_buffers_first_bind_slot;
			in.name = "persistent_input_stream";
			in.type = vk::glsl::input_type_texel_buffer;
			m_vs_inputs.push_back(in);

			in.location = binding_table.vertex_buffers_first_bind_slot + 1;
			in.name = "volatile_input_stream";
			in.type = vk::glsl::input_type_texel_buffer;
			m_vs_inputs.push_back(in);

			in.location = binding_table.vertex_buffers_first_bind_slot + 2;
			in.name = "vertex_layout_stream";
			in.type = vk::glsl::input_type_texel_buffer;
			m_vs_inputs.push_back(in);

			in.location = binding_table.vertex_constant_buffers_bind_slot;
			in.name = "VertexConstantsBuffer";
			in.type = vk::glsl::input_type_uniform_buffer;
			m_vs_inputs.push_back(in);

			// TODO: Bind textures if needed
		}

		auto ret = vs.get();
		m_shader_cache[compiler_options].m_vs = std::move(vs);
		return ret;
	}

	glsl::shader* shader_interpreter::build_fs(u64 compiler_options)
	{
		[[maybe_unused]] ::glsl::shader_properties properties{};
		properties.domain = ::glsl::program_domain::glsl_fragment_program;
		properties.require_depth_conversion = true;
		properties.require_wpos = true;

		u32 len;
		ParamArray arr;
		std::string shader_str;
		RSXFragmentProgram frag;
		VKFragmentProgram vk_prog;
		VKFragmentDecompilerThread comp(shader_str, arr, frag, len, vk_prog);

		const auto& binding_table = vk::get_current_renderer()->get_pipeline_binding_table();
		std::stringstream builder;
		builder << "#version 450\n"
				   "#extension GL_ARB_separate_shader_objects : enable\n\n";

		::glsl::insert_subheader_block(builder);
		comp.insertConstants(builder);

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_GE)
		{
			builder << "#define ALPHA_TEST_GEQUAL\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_G)
		{
			builder << "#define ALPHA_TEST_GREATER\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_LE)
		{
			builder << "#define ALPHA_TEST_LEQUAL\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_L)
		{
			builder << "#define ALPHA_TEST_LESS\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_EQ)
		{
			builder << "#define ALPHA_TEST_EQUAL\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_NE)
		{
			builder << "#define ALPHA_TEST_NEQUAL\n";
		}

		if (!(compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_F32_EXPORT))
		{
			builder << "#define WITH_HALF_OUTPUT_REGISTER\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_DEPTH_EXPORT)
		{
			builder << "#define WITH_DEPTH_EXPORT\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_FLOW_CTRL)
		{
			builder << "#define WITH_FLOW_CTRL\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_PACKING)
		{
			builder << "#define WITH_PACKING\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_KIL)
		{
			builder << "#define WITH_KIL\n";
		}

		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_STIPPLING)
		{
			builder << "#define WITH_STIPPLING\n";
		}

		const char* type_names[] = {"sampler1D", "sampler2D", "sampler3D", "samplerCube"};
		if (compiler_options & program_common::interpreter::COMPILER_OPT_ENABLE_TEXTURES)
		{
			builder << "#define WITH_TEXTURES\n\n";

			for (int i = 0, bind_location = m_fragment_textures_start; i < 4; ++i)
			{
				builder << "layout(set=0, binding=" << bind_location++ << ") " << "uniform " << type_names[i] << " " << type_names[i] << "_array[16];\n";
			}

			builder << "\n"
					   "#define IS_TEXTURE_RESIDENT(index) true\n"
					   "#define SAMPLER1D(index) sampler1D_array[index]\n"
					   "#define SAMPLER2D(index) sampler2D_array[index]\n"
					   "#define SAMPLER3D(index) sampler3D_array[index]\n"
					   "#define SAMPLERCUBE(index) samplerCube_array[index]\n\n";
		}

		builder << "layout(std430, binding=" << m_fragment_instruction_start << ") readonly restrict buffer FragmentInstructionBlock\n"
																				"{\n"
																				"	uint shader_control;\n"
																				"	uint texture_control;\n"
																				"	uint reserved1;\n"
																				"	uint reserved2;\n"
																				"	uvec4 fp_instructions[];\n"
																				"};\n\n";

		builder << program_common::interpreter::get_fragment_interpreter();
		const std::string s = builder.str();

		auto fs = std::make_unique<glsl::shader>();
		fs->create(::glsl::program_domain::glsl_fragment_program, s);
		fs->compile();

		if (m_fs_inputs.empty())
		{
			// Prepare input table
			vk::glsl::program_input in;
			in.location = binding_table.fragment_constant_buffers_bind_slot;
			in.domain = ::glsl::glsl_fragment_program;
			in.name = "FragmentConstantsBuffer";
			in.type = vk::glsl::input_type_uniform_buffer;
			m_fs_inputs.push_back(in);

			in.location = binding_table.fragment_state_bind_slot;
			in.name = "FragmentStateBuffer";
			m_fs_inputs.push_back(in);

			in.location = binding_table.fragment_texture_params_bind_slot;
			in.name = "TextureParametersBuffer";
			m_fs_inputs.push_back(in);

			for (int i = 0, location = m_fragment_textures_start; i < 4; ++i, ++location)
			{
				in.location = location;
				in.name = std::string(type_names[i]) + "_array[16]";
				m_fs_inputs.push_back(in);
			}
		}

		auto ret = fs.get();
		m_shader_cache[compiler_options].m_fs = std::move(fs);
		return ret;
	}

	std::pair<VkDescriptorSetLayout, VkPipelineLayout> shader_interpreter::create_layout(VkDevice dev)
	{
		const auto& binding_table = vk::get_current_renderer()->get_pipeline_binding_table();
		auto bindings = get_common_binding_table();
		u32 idx = ::size32(bindings);

		bindings.resize(binding_table.total_descriptor_bindings);

		// Texture 1D array
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		bindings[idx].descriptorCount = 16;
		bindings[idx].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot;
		bindings[idx].pImmutableSamplers = nullptr;

		m_fragment_textures_start = bindings[idx].binding;
		idx++;

		// Texture 2D array
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		bindings[idx].descriptorCount = 16;
		bindings[idx].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot + 1;
		bindings[idx].pImmutableSamplers = nullptr;

		idx++;

		// Texture 3D array
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		bindings[idx].descriptorCount = 16;
		bindings[idx].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot + 2;
		bindings[idx].pImmutableSamplers = nullptr;

		idx++;

		// Texture CUBE array
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		bindings[idx].descriptorCount = 16;
		bindings[idx].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot + 3;
		bindings[idx].pImmutableSamplers = nullptr;

		idx++;

		// Vertex texture array (2D only)
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
		bindings[idx].descriptorCount = 4;
		bindings[idx].stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot + 4;
		bindings[idx].pImmutableSamplers = nullptr;

		idx++;

		// Vertex program ucode block
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
		bindings[idx].descriptorCount = 1;
		bindings[idx].stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot + 5;
		bindings[idx].pImmutableSamplers = nullptr;

		m_vertex_instruction_start = bindings[idx].binding;
		idx++;

		// Fragment program ucode block
		bindings[idx].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
		bindings[idx].descriptorCount = 1;
		bindings[idx].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
		bindings[idx].binding = binding_table.textures_first_bind_slot + 6;
		bindings[idx].pImmutableSamplers = nullptr;

		m_fragment_instruction_start = bindings[idx].binding;
		idx++;
		bindings.resize(idx);

		// Compile descriptor pool sizes
		const u32 num_ubo = bindings.reduce(0, FN(x + (y.descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER ? y.descriptorCount : 0)));
		const u32 num_texel_buffers = bindings.reduce(0, FN(x + (y.descriptorType == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER ? y.descriptorCount : 0)));
		const u32 num_combined_image_sampler = bindings.reduce(0, FN(x + (y.descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ? y.descriptorCount : 0)));
		const u32 num_ssbo = bindings.reduce(0, FN(x + (y.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER ? y.descriptorCount : 0)));

		ensure(num_ubo > 0 && num_texel_buffers > 0 && num_combined_image_sampler > 0 && num_ssbo > 0);

		m_descriptor_pool_sizes =
			{
				{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, num_ubo},
				{VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, num_texel_buffers},
				{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, num_combined_image_sampler},
				{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, num_ssbo}};

		std::array<VkPushConstantRange, 1> push_constants;
		push_constants[0].offset = 0;
		push_constants[0].size = 16;
		push_constants[0].stageFlags = VK_SHADER_STAGE_VERTEX_BIT;

		if (vk::emulate_conditional_rendering())
		{
			// Conditional render toggle
			push_constants[0].size = 20;
		}

		const auto set_layout = vk::descriptors::create_layout(bindings);

		VkPipelineLayoutCreateInfo layout_info = {};
		layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
		layout_info.setLayoutCount = 1;
		layout_info.pSetLayouts = &set_layout;
		layout_info.pushConstantRangeCount = 1;
		layout_info.pPushConstantRanges = push_constants.data();

		VkPipelineLayout result;
		CHECK_RESULT(VK_GET_SYMBOL(vkCreatePipelineLayout)(dev, &layout_info, nullptr, &result));
		return {set_layout, result};
	}

	void shader_interpreter::create_descriptor_pools(const vk::render_device& dev)
	{
		const auto max_draw_calls = dev.get_descriptor_max_draw_calls();
		m_descriptor_pool.create(dev, m_descriptor_pool_sizes, max_draw_calls);
	}

	void shader_interpreter::init(const vk::render_device& dev)
	{
		m_device = dev;
		std::tie(m_shared_descriptor_layout, m_shared_pipeline_layout) = create_layout(dev);
		create_descriptor_pools(dev);
	}

	void shader_interpreter::destroy()
	{
		{
			std::lock_guard lock(m_program_cache_lock);
			m_program_cache.clear();
		}

		m_descriptor_pool.destroy();

		for (auto& fs : m_shader_cache)
		{
			fs.second.m_vs->destroy();
			fs.second.m_fs->destroy();
		}

		m_shader_cache.clear();

		if (m_shared_pipeline_layout)
		{
			VK_GET_SYMBOL(vkDestroyPipelineLayout)(m_device, m_shared_pipeline_layout, nullptr);
			m_shared_pipeline_layout = VK_NULL_HANDLE;
		}

		if (m_shared_descriptor_layout)
		{
			VK_GET_SYMBOL(vkDestroyDescriptorSetLayout)(m_device, m_shared_descriptor_layout, nullptr);
			m_shared_descriptor_layout = VK_NULL_HANDLE;
		}
	}

	glsl::program* shader_interpreter::link(const vk::pipeline_props& properties, u64 compiler_opt, bool async, async_build_fn_callback async_callback)
	{
		glsl::shader *fs, *vs;
		if (auto found = m_shader_cache.find(compiler_opt); found != m_shader_cache.end())
		{
			fs = found->second.m_fs.get();
			vs = found->second.m_vs.get();
		}
		else
		{
			fs = build_fs(compiler_opt);
			vs = build_vs(compiler_opt);
		}

		VkShaderModule module_handles[2] = {vs->get_handle(), fs->get_handle()};
		const auto compiler_flags = async ? vk::pipe_compiler::COMPILE_DEFERRED : vk::pipe_compiler::COMPILE_INLINE;

		auto callback = [this, key = pipeline_key{compiler_opt, properties}, async_callback](std::unique_ptr<glsl::program>& program)
		{
			if (!program)
			{
				return;
			}

			glsl::program* result = program.get();
			{
				std::lock_guard lock(m_program_cache_lock);
				if (auto found = m_program_cache.find(key); found != m_program_cache.end())
				{
					result = found->second.get();
				}
				else
				{
					m_program_cache.emplace(key, std::move(program));
				}
			}

			if (async_callback)
			{
				async_callback(result);
			}
		};

		auto compiler = vk::get_pipe_compiler();
		auto program = compiler->compile(properties, module_handles, m_shared_pipeline_layout, compiler_flags, callback, m_vs_inputs, m_fs_inputs);
		if (async)
		{
			return nullptr;
		}

		return program.release();
	}

	void shader_interpreter::update_fragment_textures(const std::array<VkDescriptorImageInfo, 68>& sampled_images, vk::descriptor_set& set)
	{
		const VkDescriptorImageInfo* texture_ptr = sampled_images.data();
		for (u32 i = 0, binding = m_fragment_textures_start; i < 4; ++i, ++binding, texture_ptr += 16)
		{
			set.push(texture_ptr, 16, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, binding);
		}
	}

	VkDescriptorSet shader_interpreter::allocate_descriptor_set()
	{
		return m_descriptor_pool.allocate(m_shared_descriptor_layout);
	}

	void shader_interpreter::preload(rsx::shader_loading_dialog* dlg)
	{
		const auto variants = get_interpreter_preload_variants();
		if (variants.empty())
		{
			return;
		}

		if (dlg)
		{
			dlg->create("Precompiling Vulkan interpreter variants.\nPlease wait...", "Shader Compilation");
			dlg->set_limit(0, ::size32(variants));
			dlg->update_msg(0, "Building interpreter shader modules...");
			dlg->set_value(0, 0);
		}

		u32 built_modules = 0;
		for (const u64 compiler_opt : variants)
		{
			if (!m_shader_cache.contains(compiler_opt))
			{
				build_fs(compiler_opt);
				build_vs(compiler_opt);
			}

			if (dlg)
			{
				dlg->set_value(0, ++built_modules);
			}
		}

		const auto pipe_properties = get_interpreter_preload_pipelines();
		const u32 pipeline_limit = ::size32(variants) * ::size32(pipe_properties);
		if (!pipeline_limit)
		{
			if (dlg)
			{
				dlg->close();
			}

			return;
		}

		if (dlg)
		{
			dlg->set_limit(1, pipeline_limit);
			dlg->update_msg(1, "Building interpreter pipeline variants...");
			dlg->set_value(1, 0);
		}

		atomic_t<u32> completed = 0;
		u32 queued = 0;
		for (const auto& props : pipe_properties)
		{
			for (const u64 compiler_opt : variants)
			{
				pipeline_key key{compiler_opt, props};
				{
					reader_lock lock(m_program_cache_lock);
					if (m_program_cache.contains(key))
					{
						continue;
					}
				}

				queued++;
				link(props, compiler_opt, true, [&](glsl::program*)
				{
					completed++;
				});
			}
		}

		while (completed.load() < queued && !Emu.IsStopped())
		{
			if (dlg)
			{
				const u32 count = completed.load();
				dlg->set_value(1, count);
				dlg->update_msg(1, fmt::format("Building interpreter pipeline %u of %u...", count, queued));
			}

			std::this_thread::sleep_for(std::chrono::milliseconds(16));
		}

		if (dlg)
		{
			dlg->set_value(1, queued);
			dlg->refresh();
			dlg->close();
		}
	}

	glsl::program* shader_interpreter::get(
		const vk::pipeline_props& properties,
		const program_hash_util::fragment_program_utils::fragment_program_metadata& metadata,
		u32 vp_ctrl,
		u32 fp_ctrl)
	{
		pipeline_key key;
		key.compiler_opt = 0;
		key.properties = properties;

		if (rsx::method_registers.alpha_test_enabled()) [[unlikely]]
		{
			switch (rsx::method_registers.alpha_func())
			{
			case rsx::comparison_function::always:
				break;
			case rsx::comparison_function::never:
				return nullptr;
			case rsx::comparison_function::greater_or_equal:
				key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_GE;
				break;
			case rsx::comparison_function::greater:
				key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_G;
				break;
			case rsx::comparison_function::less_or_equal:
				key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_LE;
				break;
			case rsx::comparison_function::less:
				key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_L;
				break;
			case rsx::comparison_function::equal:
				key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_EQ;
				break;
			case rsx::comparison_function::not_equal:
				key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_ALPHA_TEST_NE;
				break;
			}
		}

		if (fp_ctrl & CELL_GCM_SHADER_CONTROL_DEPTH_EXPORT)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_DEPTH_EXPORT;
		if (fp_ctrl & CELL_GCM_SHADER_CONTROL_32_BITS_EXPORTS)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_F32_EXPORT;
		if (fp_ctrl & RSX_SHADER_CONTROL_USES_KIL)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_KIL;
		if (metadata.referenced_textures_mask)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_TEXTURES;
		if (metadata.has_branch_instructions)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_FLOW_CTRL;
		if (metadata.has_pack_instructions)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_PACKING;
		if (rsx::method_registers.polygon_stipple_enabled())
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_STIPPLING;
		if (vp_ctrl & RSX_SHADER_CONTROL_INSTANCED_CONSTANTS)
			key.compiler_opt |= program_common::interpreter::COMPILER_OPT_ENABLE_INSTANCING;

		if (m_current_key == key) [[likely]]
		{
			return m_current_interpreter;
		}
		else
		{
			m_current_key = key;
		}

		{
			reader_lock lock(m_program_cache_lock);
			auto found = m_program_cache.find(key);
			if (found != m_program_cache.end()) [[likely]]
			{
				m_current_interpreter = found->second.get();
				return m_current_interpreter;
			}
		}

		const auto start = std::chrono::steady_clock::now();
		auto linked = link(properties, key.compiler_opt);
		const auto end = std::chrono::steady_clock::now();

		if (!linked)
		{
			return nullptr;
		}

		const auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
		if (duration > std::chrono::milliseconds(1000))
		{
			rsx_log.warning("Vulkan interpreter cache miss took %lld ms (compiler_opt=0x%llx)", static_cast<s64>(duration.count()), key.compiler_opt);
		}

		{
			std::lock_guard lock(m_program_cache_lock);
			if (auto found = m_program_cache.find(key); found != m_program_cache.end())
			{
				std::unique_ptr<glsl::program> discard(linked);
				m_current_interpreter = found->second.get();
			}
			else
			{
				m_current_interpreter = linked;
				m_program_cache[key].reset(m_current_interpreter);
			}
		}

		return m_current_interpreter;
	}

	bool shader_interpreter::is_interpreter(const glsl::program* prog) const
	{
		return prog == m_current_interpreter;
	}

	u32 shader_interpreter::get_vertex_instruction_location() const
	{
		return m_vertex_instruction_start;
	}

	u32 shader_interpreter::get_fragment_instruction_location() const
	{
		return m_fragment_instruction_start;
	}
}; // namespace vk
